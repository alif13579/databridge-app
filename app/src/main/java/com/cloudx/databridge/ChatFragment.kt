package com.cloudx.databridge

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

/**
 * 💬 Chat Fragment
 * Access: all authenticated users (nav_chat permission)
 *
 * Firebase paths:
 *  chats/{chat_id}/messages/{push_id}/   ← messages
 *  chats/{chat_id}/meta/                 ← last message, participants
 *  user_chats/{uid}/{chat_id}: true      ← user's chat index
 */
class ChatFragment : Fragment() {

    // ── Data classes ──────────────────────────────────────────────────
    data class ChatUser(
        val uid: String = "",
        val name: String = "",
        val phone: String = "",
        val role: String = "",
        val branch: String = "",
        val lastActive: Long = 0L,
    )

    data class ChatItem(
        val chatId: String = "",
        val otherUser: ChatUser = ChatUser(),
        val lastMessage: String = "",
        val lastMessageAt: Long = 0L,
        val unreadCount: Int = 0,
    )

    data class Message(
        val id: String = "",
        val senderUid: String = "",
        val text: String = "",
        val sentAt: Long = 0L,
    )

    // ── State ──────────────────────────────────────────────────────────
    private enum class Screen { LIST, SEARCH, CONVERSATION }
    private var screen = Screen.LIST

    private var activeChat: ChatItem? = null
    private var activeChatUser: ChatUser? = null

    private val db   = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val myUid get() = auth.currentUser?.uid ?: ""

    private var chatListListener: ValueEventListener? = null
    private var messagesListener: ValueEventListener? = null
    private var activeSearchJob: Job? = null

    // ── Chat list adapter ─────────────────────────────────────────────
    private val chatItems = mutableListOf<ChatItem>()
    private val filteredItems = mutableListOf<ChatItem>()
    private lateinit var chatAdapter: ChatListAdapter

    // ── Messages adapter ──────────────────────────────────────────────
    private val messages = mutableListOf<Message>()
    private lateinit var msgAdapter: MessageAdapter

    // ── Views ─────────────────────────────────────────────────────────
    private var screenList: View? = null
    private var screenSearch: View? = null
    private var screenConversation: View? = null

    // List screen
    private var tvChatCount: TextView? = null
    private var btnNewChat: TextView? = null
    private var etChatSearch: EditText? = null
    private var rvChatList: RecyclerView? = null
    private var layoutChatEmpty: View? = null

    // Search screen
    private var btnSearchBack: TextView? = null
    private var etPhoneSearch: EditText? = null
    private var layoutSearchPrompt: View? = null
    private var pbSearching: ProgressBar? = null
    private var layoutSearchNotFound: View? = null
    private var cardSearchResult: View? = null
    private var tvSearchAvatar: TextView? = null
    private var tvSearchName: TextView? = null
    private var tvSearchRole: TextView? = null
    private var tvSearchPhone: TextView? = null
    private var tvSearchBranch: TextView? = null
    private var btnStartChat: TextView? = null

    // Conversation screen
    private var btnConvBack: TextView? = null
    private var tvConvAvatar: TextView? = null
    private var tvConvName: TextView? = null
    private var tvConvRole: TextView? = null
    private var tvConvStatus: TextView? = null
    private var tvConvPhone: TextView? = null
    private var rvMessages: RecyclerView? = null
    private var etMessageInput: EditText? = null
    private var btnSendMessage: TextView? = null

    // ── Lifecycle ─────────────────────────────────────────────────────
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_chat, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupAdapters()
        attachListeners()
        render()
        loadChatList()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        chatListListener?.let { db.reference.child("user_chats/$myUid").removeEventListener(it) }
        messagesListener?.let { db.reference.removeEventListener(it) }
        activeSearchJob?.cancel()
    }

    // ── Bind views ────────────────────────────────────────────────────
    private fun bindViews(v: View) {
        screenList         = v.findViewById(R.id.screenChatList)
        screenSearch       = v.findViewById(R.id.screenSearch)
        screenConversation = v.findViewById(R.id.screenConversation)

        tvChatCount   = v.findViewById(R.id.tvChatCount)
        btnNewChat    = v.findViewById(R.id.btnNewChat)
        etChatSearch  = v.findViewById(R.id.etChatSearch)
        rvChatList    = v.findViewById(R.id.rvChatList)
        layoutChatEmpty = v.findViewById(R.id.layoutChatEmpty)

        btnSearchBack       = v.findViewById(R.id.btnSearchBack)
        etPhoneSearch       = v.findViewById(R.id.etPhoneSearch)
        layoutSearchPrompt  = v.findViewById(R.id.layoutSearchPrompt)
        pbSearching         = v.findViewById(R.id.pbSearching)
        layoutSearchNotFound= v.findViewById(R.id.layoutSearchNotFound)
        cardSearchResult    = v.findViewById(R.id.cardSearchResult)
        tvSearchAvatar      = v.findViewById(R.id.tvSearchAvatar)
        tvSearchName        = v.findViewById(R.id.tvSearchName)
        tvSearchRole        = v.findViewById(R.id.tvSearchRole)
        tvSearchPhone       = v.findViewById(R.id.tvSearchPhone)
        tvSearchBranch      = v.findViewById(R.id.tvSearchBranch)
        btnStartChat        = v.findViewById(R.id.btnStartChat)

        btnConvBack     = v.findViewById(R.id.btnConvBack)
        tvConvAvatar    = v.findViewById(R.id.tvConvAvatar)
        tvConvName      = v.findViewById(R.id.tvConvName)
        tvConvRole      = v.findViewById(R.id.tvConvRole)
        tvConvStatus    = v.findViewById(R.id.tvConvStatus)
        tvConvPhone     = v.findViewById(R.id.tvConvPhone)
        rvMessages      = v.findViewById(R.id.rvMessages)
        etMessageInput  = v.findViewById(R.id.etMessageInput)
        btnSendMessage  = v.findViewById(R.id.btnSendMessage)
    }

    // ── Adapters ──────────────────────────────────────────────────────
    private fun setupAdapters() {
        chatAdapter = ChatListAdapter(filteredItems) { item ->
            activeChat = item
            activeChatUser = item.otherUser
            screen = Screen.CONVERSATION
            render()
            loadMessages(item.chatId)
        }
        rvChatList?.layoutManager = LinearLayoutManager(requireContext())
        rvChatList?.adapter = chatAdapter

        msgAdapter = MessageAdapter(messages, myUid)
        rvMessages?.layoutManager = LinearLayoutManager(requireContext()).also { it.stackFromEnd = true }
        rvMessages?.adapter = msgAdapter
    }

    // ── Listeners ─────────────────────────────────────────────────────
    private fun attachListeners() {
        btnNewChat?.setOnClickListener { screen = Screen.SEARCH; render() }
        btnSearchBack?.setOnClickListener { screen = Screen.LIST; render() }
        btnConvBack?.setOnClickListener {
            messagesListener?.let { l -> db.reference.removeEventListener(l) }
            screen = Screen.LIST; render()
        }

        // Chat list search filter
        etChatSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { filterChatList(s?.toString() ?: "") }
        })

        // Phone search with debounce
        etPhoneSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                activeSearchJob?.cancel()
                if (query.length < 5) {
                    showSearchState("prompt"); return
                }
                activeSearchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(500)
                    searchUserByPhone(query)
                }
            }
        })

        btnStartChat?.setOnClickListener {
            val user = activeChatUser ?: return@setOnClickListener
            val chatId = buildChatId(myUid, user.uid)
            activeChat = ChatItem(chatId = chatId, otherUser = user)
            screen = Screen.CONVERSATION
            render()
            loadMessages(chatId)
        }

        btnSendMessage?.setOnClickListener { sendMessage() }
    }

    // ── Render ────────────────────────────────────────────────────────
    private fun render() {
        screenList?.visibility         = if (screen == Screen.LIST)         View.VISIBLE else View.GONE
        screenSearch?.visibility       = if (screen == Screen.SEARCH)       View.VISIBLE else View.GONE
        screenConversation?.visibility = if (screen == Screen.CONVERSATION) View.VISIBLE else View.GONE

        if (screen == Screen.SEARCH) {
            etPhoneSearch?.setText("")
            showSearchState("prompt")
            etPhoneSearch?.requestFocus()
        }

        if (screen == Screen.CONVERSATION) {
            val user = activeChatUser ?: return
            val initials = user.name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("")
            tvConvAvatar?.text = initials.ifEmpty { "?" }
            tvConvName?.text   = user.name
            tvConvRole?.text   = user.role.replaceFirstChar { it.uppercase() }
            tvConvPhone?.text  = user.phone
            val (statusText, statusColor) = formatLastActive(user.lastActive)
            tvConvStatus?.text = statusText
            tvConvStatus?.setTextColor(android.graphics.Color.parseColor(statusColor))
            setAvatarColor(tvConvAvatar, user.name)
        }
    }

    // ── Firebase: load chat list ──────────────────────────────────────
    private fun loadChatList() {
        if (myUid.isEmpty()) return
        val ref = db.reference.child("user_chats/$myUid")
        chatListListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                chatItems.clear()
                val chatIds = snap.children.mapNotNull { it.key }
                if (chatIds.isEmpty()) {
                    updateChatListUI(); return
                }
                var loaded = 0
                chatIds.forEach { chatId ->
                    db.reference.child("chats/$chatId/meta").get().addOnSuccessListener { meta ->
                        val lastMsg = meta.child("last_message").getValue(String::class.java) ?: ""
                        val lastAt  = meta.child("last_message_at").getValue(Long::class.java) ?: 0L
                        val participants = meta.child("participants").children
                            .mapNotNull { it.key }.filter { it != myUid }
                        val otherUid = participants.firstOrNull() ?: ""
                        db.reference.child("users/$otherUid/profile").get().addOnSuccessListener { profile ->
                            val ci = profile.child("company_info")
                            val user = ChatUser(
                                uid        = otherUid,
                                name       = profile.child("displayName").getValue(String::class.java) ?: "Unknown",
                                phone      = ci.child("phone").getValue(String::class.java) ?: "",
                                role       = ci.child("role_id").getValue(String::class.java) ?: "",
                                branch     = ci.child("branch_ids").children.firstOrNull()?.key ?: "",
                                lastActive = profile.child("lastActive").getValue(Long::class.java) ?: 0L,
                            )
                            chatItems.add(ChatItem(chatId, user, lastMsg, lastAt))
                            chatItems.sortByDescending { it.lastMessageAt }
                            loaded++
                            if (loaded == chatIds.size) updateChatListUI()
                        }
                    }
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        ref.addValueEventListener(chatListListener!!)
    }

    private fun updateChatListUI() {
        if (!isAdded) return
        filterChatList(etChatSearch?.text?.toString() ?: "")
        tvChatCount?.text = "${chatItems.size} conversation${if (chatItems.size != 1) "s" else ""}"
    }

    private fun filterChatList(query: String) {
        filteredItems.clear()
        if (query.isEmpty()) {
            filteredItems.addAll(chatItems)
        } else {
            filteredItems.addAll(chatItems.filter {
                it.otherUser.name.contains(query, true) ||
                it.otherUser.phone.contains(query)
            })
        }
        chatAdapter.notifyDataSetChanged()
        rvChatList?.visibility     = if (filteredItems.isNotEmpty()) View.VISIBLE else View.GONE
        layoutChatEmpty?.visibility = if (filteredItems.isEmpty())   View.VISIBLE else View.GONE
    }

    // ── Firebase: search user by phone ────────────────────────────────
    private fun searchUserByPhone(phone: String) {
        showSearchState("loading")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val snap = db.reference.child("users").orderByChild("profile/company_info/phone")
                    .equalTo(phone).get().await()
                if (!isAdded) return@launch
                if (!snap.exists()) { showSearchState("notfound"); return@launch }
                val userSnap = snap.children.first()
                val uid      = userSnap.key ?: run { showSearchState("notfound"); return@launch }
                val profile  = userSnap.child("profile")
                val ci       = profile.child("company_info")
                val user = ChatUser(
                    uid        = uid,
                    name       = profile.child("displayName").getValue(String::class.java) ?: "Unknown",
                    phone      = ci.child("phone").getValue(String::class.java) ?: phone,
                    role       = ci.child("role_id").getValue(String::class.java) ?: "",
                    branch     = ci.child("branch_ids").children.firstOrNull()?.key ?: "",
                    lastActive = profile.child("lastActive").getValue(Long::class.java) ?: 0L,
                )
                activeChatUser = user
                showSearchResult(user)
            } catch (e: Exception) {
                if (isAdded) showSearchState("notfound")
            }
        }
    }

    private fun showSearchState(state: String) {
        layoutSearchPrompt?.visibility  = if (state == "prompt")   View.VISIBLE else View.GONE
        pbSearching?.visibility         = if (state == "loading")  View.VISIBLE else View.GONE
        layoutSearchNotFound?.visibility= if (state == "notfound") View.VISIBLE else View.GONE
        cardSearchResult?.visibility    = if (state == "result")   View.VISIBLE else View.GONE
    }

    private fun showSearchResult(user: ChatUser) {
        val initials = user.name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("")
        tvSearchAvatar?.text = initials.ifEmpty { "?" }
        setAvatarColor(tvSearchAvatar, user.name)
        tvSearchName?.text   = user.name
        tvSearchRole?.text   = user.role.replaceFirstChar { it.uppercase() }
        tvSearchPhone?.text  = user.phone
        tvSearchBranch?.text = user.branch
        showSearchState("result")
    }

    // ── Firebase: messages ────────────────────────────────────────────
    private fun loadMessages(chatId: String) {
        messagesListener?.let { db.reference.removeEventListener(it) }
        messages.clear()
        msgAdapter.notifyDataSetChanged()

        val ref = db.reference.child("chats/$chatId/messages")
            .orderByChild("sent_at").limitToLast(100)

        messagesListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                messages.clear()
                snap.children.forEach { s ->
                    messages.add(Message(
                        id        = s.key ?: "",
                        senderUid = s.child("sender_uid").getValue(String::class.java) ?: "",
                        text      = s.child("text").getValue(String::class.java) ?: "",
                        sentAt    = s.child("sent_at").getValue(Long::class.java) ?: 0L,
                    ))
                }
                if (!isAdded) return
                msgAdapter.notifyDataSetChanged()
                rvMessages?.scrollToPosition(messages.size - 1)
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        ref.addValueEventListener(messagesListener!!)
    }

    private fun sendMessage() {
        val text    = etMessageInput?.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return
        val chatId  = activeChat?.chatId ?: return
        val otherUid = activeChatUser?.uid ?: return
        etMessageInput?.setText("")

        val now     = System.currentTimeMillis()
        val msgRef  = db.reference.child("chats/$chatId/messages").push()
        val msgData = mapOf(
            "sender_uid" to myUid,
            "text"       to text,
            "sent_at"    to now,
        )
        val metaData = mapOf(
            "last_message"    to text,
            "last_message_at" to now,
            "participants/$myUid" to true,
            "participants/$otherUid" to true,
        )
        val updates = mutableMapOf<String, Any>(
            "chats/$chatId/messages/${msgRef.key}" to msgData,
            "chats/$chatId/meta/last_message"      to text,
            "chats/$chatId/meta/last_message_at"   to now,
            "chats/$chatId/meta/participants/$myUid"    to true,
            "chats/$chatId/meta/participants/$otherUid" to true,
            "user_chats/$myUid/$chatId"            to true,
            "user_chats/$otherUid/$chatId"         to true,
        )
        db.reference.updateChildren(updates)
    }

    // ── Helpers ───────────────────────────────────────────────────────
    private fun buildChatId(uid1: String, uid2: String) =
        listOf(uid1, uid2).sorted().joinToString("_")

    private fun setAvatarColor(view: TextView?, name: String) {
        val colors = listOf("#E8380D", "#16A34A", "#2563EB", "#7C3AED", "#D97706")
        val color  = colors[name.firstOrNull()?.code?.rem(colors.size) ?: 0]
        view?.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(color))
    }

    private fun formatTime(ts: Long): String {
        if (ts == 0L) return ""
        val now  = System.currentTimeMillis()
        val diff = now - ts
        return when {
            diff < 86400000L  -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts))
            diff < 172800000L -> "Yesterday"
            else              -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ts))
        }
    }

    /** Returns Pair(statusText, colorHex) based on lastActive timestamp */
    private fun formatLastActive(lastActive: Long): Pair<String, String> {
        if (lastActive == 0L) return Pair("Offline", "#9CA3AF")
        val diff = System.currentTimeMillis() - lastActive
        return when {
            diff < 3 * 60 * 1000L       -> Pair("Online", "#16A34A")          // < 3 min
            diff < 60 * 60 * 1000L      -> {                                    // < 1 hour
                val mins = (diff / 60000).toInt()
                Pair("Active $mins min ago", "#D97706")
            }
            diff < 24 * 60 * 60 * 1000L -> {                                   // < 24 hours
                val hrs = (diff / 3600000).toInt()
                Pair("Active ${hrs}h ago", "#9CA3AF")
            }
            diff < 48 * 60 * 60 * 1000L -> Pair("Last seen yesterday", "#9CA3AF")
            else                         -> {
                val date = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(lastActive))
                Pair("Last seen $date", "#9CA3AF")
            }
        }
    }

    // ── Chat List Adapter ─────────────────────────────────────────────
    inner class ChatListAdapter(
        private val items: List<ChatItem>,
        private val onClick: (ChatItem) -> Unit,
    ) : RecyclerView.Adapter<ChatListAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvAvatar  : TextView = v.findViewById(R.id.tvItemAvatar)
            val tvName    : TextView = v.findViewById(R.id.tvItemName)
            val tvRole    : TextView = v.findViewById(R.id.tvItemRole)
            val tvTime    : TextView = v.findViewById(R.id.tvItemTime)
            val tvLastMsg : TextView = v.findViewById(R.id.tvItemLastMsg)
            val tvUnread  : TextView = v.findViewById(R.id.tvItemUnread)
            val tvBranch  : TextView = v.findViewById(R.id.tvItemBranch)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]
            val user = item.otherUser
            val initials = user.name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString("")
            h.tvAvatar.text   = initials.ifEmpty { "?" }
            setAvatarColor(h.tvAvatar, user.name)
            h.tvName.text     = user.name
            h.tvRole.text     = user.role.replaceFirstChar { it.uppercase() }
            h.tvTime.text     = formatTime(item.lastMessageAt)
            h.tvLastMsg.text  = item.lastMessage.ifEmpty { "Tap to start chatting" }
            h.tvBranch.text   = user.branch
            h.tvUnread.visibility = if (item.unreadCount > 0) View.VISIBLE else View.GONE
            h.tvUnread.text   = item.unreadCount.toString()
            // Online dot — green if active within 3 minutes
            val isOnline = user.lastActive > 0 && (System.currentTimeMillis() - user.lastActive) < 3 * 60 * 1000L
            h.itemView.findViewById<View>(R.id.viewOnlineDot)?.visibility = if (isOnline) View.VISIBLE else View.GONE
            h.itemView.setOnClickListener { onClick(item) }
        }
    }

    // ── Message Adapter ───────────────────────────────────────────────
    inner class MessageAdapter(
        private val items: List<Message>,
        private val myUid: String,
    ) : RecyclerView.Adapter<MessageAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val root      : LinearLayout = v.findViewById(R.id.msgRoot)
            val bubble    : LinearLayout = v.findViewById(R.id.bubbleContainer)
            val tvText    : TextView     = v.findViewById(R.id.tvMsgText)
            val tvTime    : TextView     = v.findViewById(R.id.tvMsgTime)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val msg  = items[pos]
            val isMe = msg.senderUid == myUid

            h.tvText.text = msg.text
            h.tvTime.text = formatTime(msg.sentAt)

            // Align + color based on sender
            h.root.gravity = if (isMe) Gravity.END else Gravity.START
            h.bubble.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (isMe) Color.parseColor("#E8380D") else Color.parseColor("#FFFFFF")
            )
            h.tvText.setTextColor(if (isMe) Color.WHITE else Color.parseColor("#111827"))
            h.tvTime.setTextColor(if (isMe) Color.parseColor("#FFCCBC") else Color.parseColor("#9CA3AF"))
        }
    }
}
