package org.fenixuz.folders

import android.content.Context
import android.content.SharedPreferences
import org.fenixuz.ui.create_folder_dialog.FolderIcons
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.ChatObject
import org.telegram.messenger.DialogObject
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.messenger.support.LongSparseIntArray
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.FilterCreateActivity

/**
 * Four folders that sort the user's groups and channels by the rights they hold in them:
 * groups they own, groups they administer, channels they own, channels they administer.
 *
 * Two things about this are worth stating plainly, because they shape every decision below.
 *
 * FIRST: Telegram folders live on the SERVER, not on this device. Creating them writes to the user's
 * account and the folders show up in official Telegram on their phone, desktop and web. That is why
 * nothing here happens without an explicit confirmation, and why turning the feature off offers to
 * remove exactly the four folders we made and nothing else.
 *
 * SECOND: Telegram has no "I am an admin here" filter flag — the auto-include flags are only
 * contacts / non-contacts / groups / broadcasts / bots. So membership has to be an explicit peer list,
 * which makes these folders a SNAPSHOT: a group the user is promoted in tomorrow will not appear on its
 * own, and one they are demoted from will linger. Hence [refresh], and hence the wording of string 397.
 */
object AdminFolders {

    private const val PREF = "db"
    private const val KEY_IDS_PREFIX = "admin_folder_ids_"
    // What each folder held last time we synced, so we can tell a real rights change from a chat the user
    // added or removed by hand. Without it an auto-sync would have to rewrite the whole list and would
    // silently undo their edits.
    private const val KEY_SNAP_PREFIX = "admin_folder_snap_"
    /**
     * Telegram refuses a folder whose title is longer than this with 400 MESSAGE_TOO_LONG, and the folder
     * is then dropped again the next time filters are fetched from the server -- which looks exactly like
     * "it saved and then vanished". FilterCreateActivity.MAX_NAME_LENGTH is the same number; we clamp
     * rather than trust the translations, so a longer wording later cannot break creation.
     */
    private const val MAX_FOLDER_NAME = 12

    /** At most one auto-sync per this interval; a rights change does not need a faster reaction. */
    private const val SYNC_MIN_INTERVAL_MS = 10_000L
    private val lastSyncAt = LongArray(UserConfig.MAX_ACCOUNT_COUNT)

    /** Owner of the folders we recorded, so state cannot survive a logout into a different account. */
    private const val KEY_UID_PREFIX = "admin_folder_uid_"

    /** The four buckets, in the order they are created (and therefore shown). */
    enum class Kind(val titleCode: Int, val iconRes: Int) {
        GROUP_OWNER(393, R.drawable.msg_groups),
        GROUP_ADMIN(394, R.drawable.msg_folders_groups),
        CHANNEL_OWNER(395, R.drawable.msg_channel),
        CHANNEL_ADMIN(396, R.drawable.msg_folders_channels);

        val title: String get() = LanguageCode.getMyTitles(titleCode).take(MAX_FOLDER_NAME)
    }

    /** What a create/refresh actually managed to do, so the caller can tell the user the truth. */
    class Result(
        /** Folders written to the account. */
        val created: Int,
        /** Chats filed across all four. */
        val filed: Int,
        /** Folders whose list was cut to Telegram's per-folder limit, as "<folder title>" to count. */
        val truncated: LinkedHashMap<String, Int>,
        /** Set when nothing could be done at all; already localized, ready to show. */
        val blockedReason: String?
    )

    private fun prefs(): SharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun keyIds(account: Int) = KEY_IDS_PREFIX + account
    private fun keySnap(account: Int) = KEY_SNAP_PREFIX + account
    private fun keyUid(account: Int) = KEY_UID_PREFIX + account

    /**
     * Account slots are reused: log out of index 0 and the next account signs in as index 0 too. Filter ids
     * start at 2, so the ids we recorded would very likely collide with folders belonging to the NEW user --
     * and a sync would then rewrite a stranger's folder with our peer list. Tying the state to the user id
     * that produced it makes that impossible, and self-heals without needing a logout listener.
     */
    private fun stateBelongsToCurrentUser(account: Int): Boolean {
        val uid = UserConfig.getInstance(account).clientUserId
        if (uid == 0L) return false
        val stored = prefs().getLong(keyUid(account), 0L)
        if (stored == uid) return true
        if (stored == 0L) {
            // No stamp: either there is nothing recorded, or the folders were made before this check
            // existed. Adopt the latter for the signed-in user instead of discarding it -- rejecting
            // unstamped state is what made the feature read as off for anyone who had already enabled it.
            val hasState = !prefs().getString(keyIds(account), "").isNullOrEmpty()
            if (!hasState) return false
            prefs().edit().putLong(keyUid(account), uid).apply()
            return true
        }
        // Stamped by a different user: this slot was reused after a logout. Drop it rather than acting on
        // folders that belong to somebody else.
        prefs().edit().remove(keyIds(account)).remove(keySnap(account)).remove(keyUid(account)).apply()
        return false
    }

    /** kind ordinal -> filter id, for the folders we own. */
    private fun kindToFilter(account: Int): LinkedHashMap<Kind, Int> {
        val out = LinkedHashMap<Kind, Int>()
        if (!stateBelongsToCurrentUser(account)) return out
        val raw = prefs().getString(keyIds(account), "") ?: ""
        for (part in raw.split(',')) {
            val bits = part.split(':')
            if (bits.size != 2) continue
            val k = bits[0].toIntOrNull() ?: continue
            val id = bits[1].toIntOrNull() ?: continue
            if (k in Kind.entries.indices) out[Kind.entries[k]] = id
        }
        return out
    }

    private fun saveKindToFilter(account: Int, map: Map<Kind, Int>) {
        prefs().edit()
            .putString(keyIds(account), map.entries.joinToString(",") { it.key.ordinal.toString() + ":" + it.value })
            .putLong(keyUid(account), UserConfig.getInstance(account).clientUserId)
            .apply()
    }

    /** The ids we filed per kind at the last sync. */
    private fun snapshot(account: Int): LinkedHashMap<Kind, MutableSet<Long>> {
        val out = LinkedHashMap<Kind, MutableSet<Long>>()
        for (k in Kind.entries) out[k] = LinkedHashSet()
        val raw = prefs().getString(keySnap(account), "") ?: ""
        for ((i, chunk) in raw.split(';').withIndex()) {
            if (i !in Kind.entries.indices || chunk.isEmpty()) continue
            for (d in chunk.split(',')) d.toLongOrNull()?.let { out[Kind.entries[i]]!!.add(it) }
        }
        return out
    }

    private fun saveSnapshot(account: Int, snap: Map<Kind, out Collection<Long>>) {
        val raw = Kind.entries.joinToString(";") { k -> (snap[k] ?: emptyList()).joinToString(",") }
        prefs().edit().putString(keySnap(account), raw).apply()
    }

    /**
     * Every title this feature has ever given a folder OF THIS KIND, in every language it ships.
     *
     * Identity is the stored kind->id map; this is the recovery net for when that map is unavailable, and
     * it has to be per-kind or a sweep would file the channels folder under groups. Two real cases need it:
     * the titles were shortened once to fit Telegram's 12-character cap, and the user can switch the app
     * language at any time -- in both, a folder we made no longer carries the title we would generate now.
     * Matching only the current title is what produced a duplicate set of four.
     *
     * A server-side marker would be better, but the folder's `emoticon` field is not on
     * MessagesController.DialogFilter and saveFilterToServer does not send it, so keeping one would mean
     * patching upstream's model and its save path -- a debt that comes due at every re-base.
     */
    private fun titlesFor(kind: Kind): Set<String> {
        val out = LinkedHashSet<String>()
        LanguageCode.getMyTitles(kind.titleCode)          // forces the table to initialize
        LanguageCode.titlesLanguages.getOrNull(kind.titleCode)?.let { t ->
            for (v in listOf(t.en, t.uz, t.ru)) {
                out.add(v)
                out.add(v.take(MAX_FOLDER_NAME))          // what actually reached the server
            }
        }
        out.addAll(LEGACY_TITLES[kind] ?: emptyList())
        out.remove("")
        return out
    }

    /** Titles used before the 12-character clamp; folders created then still carry them. */
    private val LEGACY_TITLES: Map<Kind, List<String>> = mapOf(
        Kind.GROUP_OWNER to listOf("Mening guruhlarim", "My groups", "Мои группы"),
        Kind.GROUP_ADMIN to listOf("Admin guruhlar", "Admin groups", "Группы-админ"),
        Kind.CHANNEL_OWNER to listOf("Mening kanallarim", "My channels", "Мои каналы"),
        Kind.CHANNEL_ADMIN to listOf("Admin kanallar", "Admin channels", "Каналы-админ")
    )

    /** Ids of the folders WE created for [account]. Empty means the feature is off. */
    fun createdIds(account: Int): List<Int> = kindToFilter(account).values.toList()

    @JvmStatic
    fun isEnabled(account: Int): Boolean = createdIds(account).isNotEmpty()

    /**
     * Bucket every chat the user has rights in. Local only — `creator` and `admin_rights` ride along on
     * the TLRPC.Chat we already hold for each dialog, so this costs no network and no disk.
     *
     * Owners are deliberately kept OUT of the admin buckets: [ChatObject.hasAdminRights] answers true for
     * a creator too, and leaving that in would make "My groups" and "Admin groups" near-duplicates.
     */
    fun classify(account: Int): LinkedHashMap<Kind, MutableList<Long>> {
        val buckets = LinkedHashMap<Kind, MutableList<Long>>()
        for (k in Kind.entries) buckets[k] = ArrayList()

        val controller = MessagesController.getInstance(account)
        for (dialog in ArrayList(controller.getAllDialogs())) {
            val did = dialog?.id ?: continue
            if (!DialogObject.isChatDialog(did)) continue
            val chat: TLRPC.Chat = controller.getChat(-did) ?: continue
            // Left, kicked, or deactivated: the chat is still in the list but holding rights there is
            // meaningless, and filing it would look like the folder is showing dead chats.
            if (ChatObject.isNotInChat(chat) || chat.left || chat.kicked) continue
            if (!ChatObject.hasAdminRights(chat)) continue

            val owner = ChatObject.isCreator(chat)
            // A broadcast channel is a channel that is NOT a megagroup; everything else (megagroup,
            // legacy chat) counts as a group, which is how users think about it.
            val broadcast = ChatObject.isChannel(chat) && !chat.megagroup
            val kind = when {
                broadcast && owner -> Kind.CHANNEL_OWNER
                broadcast -> Kind.CHANNEL_ADMIN
                owner -> Kind.GROUP_OWNER
                else -> Kind.GROUP_ADMIN
            }
            buckets[kind]!!.add(did)
        }
        return buckets
    }

    private fun folderLimit(account: Int): Int {
        val c = MessagesController.getInstance(account)
        return if (UserConfig.getInstance(account).isPremium) c.dialogFiltersLimitPremium else c.dialogFiltersLimitDefault
    }

    private fun chatsPerFolderLimit(account: Int): Int {
        val c = MessagesController.getInstance(account)
        return if (UserConfig.getInstance(account).isPremium) c.dialogFiltersChatsLimitPremium else c.dialogFiltersChatsLimitDefault
    }

    /** First id not already taken by one of the user's folders — the allocation Telegram itself uses. */
    private fun freeFilterId(account: Int, alsoTaken: Set<Int>): Int {
        val byId = MessagesController.getInstance(account).dialogFiltersById
        var id = 2
        while (byId.get(id) != null || alsoTaken.contains(id)) id++
        return id
    }

    /**
     * Create the folders that have anything in them. Empty buckets are skipped rather than created empty —
     * an empty "Admin channels" tab is noise for someone who administers none.
     *
     * [fragment] is required: [FilterCreateActivity.saveFilterToServer] needs a live parent activity, so
     * this runs from a screen and never from a background task.
     */
    fun create(fragment: BaseFragment, account: Int, onDone: (Result) -> Unit) {
        val buckets = classify(account).filterValues { it.isNotEmpty() }
        if (buckets.isEmpty()) {
            onDone(Result(0, 0, LinkedHashMap(), LanguageCode.getMyTitles(399)))
            return
        }

        val existing = MessagesController.getInstance(account).dialogFilters.size
        val free = folderLimit(account) - existing
        if (free < buckets.size) {
            val msg = LanguageCode.getMyTitles(400)
                .replace("%1\$d", folderLimit(account).toString())
                .replace("%2\$d", existing.toString())
            onDone(Result(0, 0, LinkedHashMap(), msg))
            return
        }

        val perFolder = chatsPerFolderLimit(account)
        val truncated = LinkedHashMap<String, Int>()
        val taken = HashSet<Int>()
        val newIds = ArrayList<Int>()
        var filed = 0

        val queue = ArrayDeque(buckets.entries.map { it.key to it.value })

        fun step() {
            val next = queue.removeFirstOrNull()
            if (next == null) {
                // MessagesController.addFilter() does NOT post this (removeFilter does), so without it the
                // tabs only appear after DialogsActivity is recreated -- which reads as "nothing happened".
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated)
                onDone(Result(newIds.size, filed, truncated, null))
                return
            }
            // saveFilterToServer returns WITHOUT calling onFinish when the fragment has lost its activity
            // (the user navigated away mid-chain), which would strand the queue forever. Stop here instead;
            // the ids of the folders already made are persisted as we go, so turning the feature off can
            // still clean them up.
            if (fragment.parentActivity == null) {
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated)
                onDone(Result(newIds.size, filed, truncated, null))
                return
            }
            val (kind, peersAll) = next
            // Cut to the server's limit rather than letting the request fail, and remember that we did so —
            // silently dropping chats is exactly the kind of thing a user reports as "the folder is wrong".
            val peers = if (peersAll.size > perFolder) {
                truncated[kind.title] = perFolder
                ArrayList(peersAll.subList(0, perFolder))
            } else {
                ArrayList(peersAll)
            }

            // Adopt a folder of ours that we have lost track of instead of making a second one. This
            // happens after a reinstall, after clearing app data, or if our own bookkeeping ever breaks --
            // and without it the user ends up with a duplicate set every time they toggle the feature.
            // Matched on the exact title we generate, and only among folders we are not already tracking.
            val tracked = createdIds(account).toSet()
            val known = titlesFor(kind)
            val adopted = MessagesController.getInstance(account).dialogFilters
                .firstOrNull { it != null && !it.isDefault && it.name in known && it.id !in tracked && it.id !in taken }

            val creating = adopted == null
            val filter = adopted ?: MessagesController.DialogFilter()
            if (creating) {
                filter.id = freeFilterId(account, taken)
                filter.neverShow = ArrayList()
                filter.pinnedDialogs = LongSparseIntArray()
            }
            taken.add(filter.id)
            filter.name = kind.title
            filter.flags = 0                     // no auto-include: membership is exactly [alwaysShow]
            filter.color = kind.ordinal % 8
            filter.alwaysShow = peers

            newIds.add(filter.id)
            filed += peers.size
            val map = kindToFilter(account); map[kind] = filter.id; saveKindToFilter(account, map)
            val snap = snapshot(account); snap[kind] = LinkedHashSet(peers); saveSnapshot(account, snap)
            // Our folders carry flags = 0 by design, and FolderIcons' flag-based guess only knows the
            // built-in filter types -- so without an explicit icon all four land on the generic one.
            FolderIcons.setIconRes(filter.id, kind.iconRes)

            FilterCreateActivity.saveFilterToServer(
                filter, filter.flags, filter.name, filter.entities, filter.title_noanimate, filter.color,
                filter.alwaysShow, filter.neverShow, filter.pinnedDialogs,
                /* creatingNew */ creating, /* atBegin */ false, /* hasUserChanged */ true,
                /* resetUnreadCounter */ false, /* progress */ false, fragment
            ) { step() }   // strictly sequential: parallel saves race on the server's filter order
        }
        step()
    }

    /**
     * Recompute and rewrite the folders we own. Same snapshot rules as [create]; folders the user has since
     * deleted by hand are dropped from our list rather than recreated, because recreating something the
     * user deliberately removed is worse than leaving it gone.
     */
    fun refresh(fragment: BaseFragment, account: Int, onDone: (Result) -> Unit) {
        val ours = createdIds(account)
        if (ours.isEmpty()) {
            create(fragment, account, onDone)
            return
        }
        remove(fragment, account) {
            create(fragment, account, onDone)
        }
    }

    /**
     * Keep the folders current without the user going to Settings and pressing Refresh.
     *
     * It applies a DELTA, never a rewrite: only chats whose rights actually changed since the last sync are
     * added or removed. That matters — these are ordinary Telegram folders and the user is free to drop a
     * chat from one or add their own; rewriting the whole list every time would quietly undo that.
     *
     * No server call happens unless something really changed, and it needs a visible fragment because
     * saveFilterToServer does; with the app in the background it simply waits for the next call.
     */
    @JvmStatic
    fun syncIfNeeded(account: Int) {
        // Order matters: this is called from updateInterfaces, which fires constantly. The time check is a
        // subtraction; isEnabled() reads a preference and parses it into a map. Cheapest guard first.
        if (account < 0 || account >= lastSyncAt.size) return
        val now = System.currentTimeMillis()
        if (now - lastSyncAt[account] < SYNC_MIN_INTERVAL_MS) return
        if (!isEnabled(account)) return

        val controller = MessagesController.getInstance(account)
        // Wait for the folders to come off disk. dialogFiltersById is empty until then, and this can run at
        // cold start. Without this guard every folder looks deleted, the branch below "forgets" all four,
        // and the feature switches itself off on restart.
        if (!controller.dialogFiltersLoaded) return
        // Claim the interval here, not once work is found. classify() walks the whole dialog list and this
        // is called from a hot notification, so the CHECK is what has to be rate-limited.
        lastSyncAt[account] = now

        val map = kindToFilter(account)
        if (map.isEmpty()) return
        val current = classify(account)
        val snap = snapshot(account)
        val perFolder = chatsPerFolderLimit(account)

        // An entry is either an update of a folder we already have, or the first folder of a kind the user
        // has only just acquired: the very first group they are made admin of should not need a manual
        // Refresh to get a folder, which is what happened before this branch existed.
        val jobs = ArrayList<Triple<Kind, List<Long>, List<Long>>>()   // kind, added, removed
        val fresh = ArrayList<Kind>()
        for (kind in Kind.entries) {
            val nowList = current[kind] ?: emptyList<Long>()
            if (map.containsKey(kind)) {
                val nowSet = LinkedHashSet(nowList)
                val wasSet = snap[kind] ?: LinkedHashSet()
                val added = nowSet.filter { it !in wasSet }
                val removed = wasSet.filter { it !in nowSet }
                if (added.isNotEmpty() || removed.isNotEmpty()) jobs.add(Triple(kind, added, removed))
            } else if (nowList.isNotEmpty()) {
                fresh.add(kind)
            }
        }
        // Only make new folders while there is room; an over-limit request would just be refused, and
        // nagging about it from a background sync the user did not ask for would be noise.
        val room = folderLimit(account) - controller.dialogFilters.size
        val freshAllowed = fresh.take(maxOf(0, room))
        if (jobs.isEmpty() && freshAllowed.isEmpty()) return

        val fragment = org.telegram.ui.LaunchActivity.getLastFragment() ?: return
        if (fragment.parentActivity == null) return

        val queue = ArrayDeque<Pair<Kind, Boolean>>()               // kind, isNew
        for (j in jobs) queue.add(j.first to false)
        for (k in freshAllowed) queue.add(k to true)
        val deltas = jobs.associate { it.first to (it.second to it.third) }
        val taken = HashSet<Int>()

        fun step() {
            val next = queue.removeFirstOrNull()
            if (next == null) {
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated)
                return
            }
            if (fragment.parentActivity == null) return
            val (kind, isNew) = next

            val filter: MessagesController.DialogFilter
            val creating: Boolean
            val peers: ArrayList<Long>

            if (isNew) {
                val tracked = createdIds(account).toSet()
                val known = titlesFor(kind)
                val adopted = controller.dialogFilters
                    .firstOrNull { it != null && !it.isDefault && it.name in known && it.id !in tracked && it.id !in taken }
                creating = adopted == null
                filter = adopted ?: MessagesController.DialogFilter()
                if (creating) {
                    filter.id = freeFilterId(account, taken)
                    filter.neverShow = ArrayList()
                    filter.pinnedDialogs = LongSparseIntArray()
                }
                filter.name = kind.title
                filter.flags = 0
                filter.color = kind.ordinal % 8
                peers = ArrayList((current[kind] ?: emptyList<Long>()).take(perFolder))
                filter.alwaysShow = peers
                val m = kindToFilter(account); m[kind] = filter.id; saveKindToFilter(account, m)
                FolderIcons.setIconRes(filter.id, kind.iconRes)
            } else {
                val existing = controller.dialogFiltersById.get(map[kind] ?: -1)
                if (existing == null) {
                    // Deleted by hand or on another device: forget it rather than recreating something the
                    // user got rid of on purpose. Safe to conclude that only because dialogFiltersLoaded
                    // was checked before we got here -- a missing filter would otherwise just mean "not
                    // loaded yet".
                    val m = kindToFilter(account); m.remove(kind); saveKindToFilter(account, m)
                    val sp = snapshot(account); sp[kind] = LinkedHashSet(); saveSnapshot(account, sp)
                    return step()
                }
                creating = false
                filter = existing
                val (added, removed) = deltas[kind] ?: (emptyList<Long>() to emptyList<Long>())
                peers = ArrayList(filter.alwaysShow)
                for (did in removed) peers.remove(did)
                for (did in added) if (!peers.contains(did) && peers.size < perFolder) peers.add(did)
            }
            taken.add(filter.id)

            val sp = snapshot(account)
            sp[kind] = LinkedHashSet(current[kind] ?: emptyList())
            saveSnapshot(account, sp)

            FilterCreateActivity.saveFilterToServer(
                filter, filter.flags, filter.name, filter.entities, filter.title_noanimate, filter.color,
                peers, filter.neverShow, filter.pinnedDialogs,
                /* creatingNew */ creating, /* atBegin */ false, /* hasUserChanged */ true,
                /* resetUnreadCounter */ false, /* progress */ false, fragment
            ) { step() }
        }
        step()
    }

    /**
     * Delete the folders this feature made, on the server and locally.
     *
     * Two sources, because the tracked ids are not always the whole story. Anything our own bookkeeping
     * lost — a rename, a language switch, a reinstall, or one of my own bugs earlier in this feature's
     * life — is still on the account under a title only we generate, and leaving those behind means the
     * user has to go and delete them by hand. So the tracked ids come first and then untracked folders
     * whose title is one of ours are swept too. Titles are specific enough that a collision with a folder
     * the user named themselves is unlikely, and this only ever runs from an explicit, confirmed "remove".
     */
    fun remove(fragment: BaseFragment, account: Int, onDone: () -> Unit) {
        val controller = MessagesController.getInstance(account)
        val ids = LinkedHashSet(createdIds(account))
        val allKnown = Kind.entries.flatMap { titlesFor(it) }.toSet()
        for (f in ArrayList(controller.dialogFilters)) {
            if (f != null && !f.isDefault && f.name in allKnown) ids.add(f.id)
        }
        val queue = ArrayDeque(ids)

        fun step() {
            val id = queue.removeFirstOrNull()
            if (id == null) {
                prefs().edit().remove(keyIds(account)).remove(keySnap(account)).remove(keyUid(account)).apply()
                onDone()
                return
            }
            val filter = controller.dialogFiltersById.get(id)
            if (filter == null) {
                step()   // already gone (deleted by hand, or on another device) — nothing to undo
                return
            }
            val req = TLRPC.TL_messages_updateDialogFilter()
            req.id = id                      // no filter body = delete, the same call FilterCreateActivity makes
            fragment.connectionsManager.sendRequest(req) { _, _ ->
                org.telegram.messenger.AndroidUtilities.runOnUIThread {
                    controller.removeFilter(filter)
                    org.telegram.messenger.MessagesStorage.getInstance(account).deleteDialogFilter(filter)
                    FolderIcons.setIconRes(id, 0)   // 0 is not in ICONS -> clears our override
                    step()
                }
            }
        }
        step()
    }
}
