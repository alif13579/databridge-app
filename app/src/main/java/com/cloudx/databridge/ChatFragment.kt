package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

/**
 * 💬 Chat Fragment
 * Access: all authenticated users (nav_chat permission)
 *
 * Screens:
 *  - Chat List    → all conversations, unread count, last message
 *  - Search       → find user by phone number
 *  - Conversation → real-time message send/receive
 *
 * Firebase paths:
 *  chats/{chat_id}/messages/{push_id}/   ← message data
 *  chats/{chat_id}/meta/                 ← last message, participants
 *  user_chats/{uid}/{chat_id}: true      ← user's chat index
 */
class ChatFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_chat, container, false)
}
