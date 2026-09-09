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
    /** At most one auto-sync per this interval; a rights change does not need a faster reaction. */
    private const val SYNC_MIN_INTERVAL_MS = 10_000L
    private var lastSyncAt = 0L

    /** The four buckets, in the order they are created (and therefore shown). */
    enum class Kind(val titleCode: Int, val iconRes: Int) {
        GROUP_OWNER(393, R.drawable.msg_groups),
        GROUP_ADMIN(394, R.drawable.msg_folders_groups),
        CHANNEL_OWNER(395, R.drawable.msg_channel),
        CHANNEL_ADMIN(396, R.drawable.msg_folders_channels);

        val title: String get() = LanguageCode.getMyTitles(titleCode)
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

    /** kind ordinal -> filter id, for the folders we own. */
    private fun kindToFilter(account: Int): LinkedHashMap<Kind, Int> {
        val out = LinkedHashMap<Kind, Int>()
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
        prefs().edit().putString(keyIds(account), map.entries.joinToString(",") { it.key.ordinal.toString() + ":" + it.value }).apply()
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
            val adopted = MessagesController.getInstance(account).dialogFilters
                .firstOrNull { it != null && !it.isDefault && it.name == kind.title && it.id !in tracked && it.id !in taken }

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
        if (!isEnabled(account)) return
        val now = System.currentTimeMillis()
        if (now - lastSyncAt < SYNC_MIN_INTERVAL_MS) return

        val map = kindToFilter(account)
        if (map.isEmpty()) return
        val current = classify(account)
        val snap = snapshot(account)

        // Only kinds with a real change are touched.
        val work = LinkedHashMap<Kind, Pair<List<Long>, List<Long>>>()
        for ((kind, filterId) in map) {
            val nowSet = LinkedHashSet(current[kind] ?: emptyList())
            val wasSet = snap[kind] ?: LinkedHashSet()
            val added = nowSet.filter { it !in wasSet }
            val removed = wasSet.filter { it !in nowSet }
            if (added.isNotEmpty() || removed.isNotEmpty()) work[kind] = added to removed
        }
        if (work.isEmpty()) return

        val fragment = org.telegram.ui.LaunchActivity.getLastFragment() ?: return
        if (fragment.parentActivity == null) return
        lastSyncAt = now

        val controller = MessagesController.getInstance(account)
        val perFolder = chatsPerFolderLimit(account)
        val queue = ArrayDeque(work.entries.map { Triple(it.key, it.value.first, it.value.second) })

        fun step() {
            val next = queue.removeFirstOrNull()
            if (next == null) {
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogFiltersUpdated)
                return
            }
            val (kind, added, removed) = next
            val filterId = map[kind] ?: return step()
            val filter = controller.dialogFiltersById.get(filterId)
            if (filter == null) {
                // Deleted by hand or on another device: forget it rather than recreating something the
                // user got rid of on purpose.
                val m = kindToFilter(account); m.remove(kind); saveKindToFilter(account, m)
                val sp = snapshot(account); sp[kind] = LinkedHashSet(); saveSnapshot(account, sp)
                return step()
            }
            val peers = ArrayList(filter.alwaysShow)
            for (did in removed) peers.remove(did)
            for (did in added) if (!peers.contains(did) && peers.size < perFolder) peers.add(did)

            val sp = snapshot(account)
            sp[kind] = LinkedHashSet(current[kind] ?: emptyList())
            saveSnapshot(account, sp)

            if (fragment.parentActivity == null) return
            FilterCreateActivity.saveFilterToServer(
                filter, filter.flags, filter.name, filter.entities, filter.title_noanimate, filter.color,
                peers, filter.neverShow, filter.pinnedDialogs,
                /* creatingNew */ false, /* atBegin */ false, /* hasUserChanged */ true,
                /* resetUnreadCounter */ false, /* progress */ false, fragment
            ) { step() }
        }
        step()
    }

    /** Delete exactly the folders we created, on the server and locally. Never touches the user's own. */
    fun remove(fragment: BaseFragment, account: Int, onDone: () -> Unit) {
        val controller = MessagesController.getInstance(account)
        val queue = ArrayDeque(createdIds(account))

        fun step() {
            val id = queue.removeFirstOrNull()
            if (id == null) {
                prefs().edit().remove(keyIds(account)).remove(keySnap(account)).apply()
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
