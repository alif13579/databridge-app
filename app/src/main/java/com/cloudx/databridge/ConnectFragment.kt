package com.cloudx.databridge

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth

/**
 * Connection screen — Google-only. Signing in with the same Google account
 * links the app and the browser extension automatically (shared
 * container/container_{uid} tree), so there is no QR scan and no manual
 * extension-ID input anymore.
 */
class ConnectFragment : Fragment() {
    private var _binding: View? = null; val binding get() = _binding!!
    private val auth = FirebaseAuth.getInstance()

    private lateinit var layoutDisconnected: LinearLayout; lateinit var tvStatus: TextView
    private lateinit var layoutGoogleSection: LinearLayout; lateinit var btnGoogleSignIn: Button
    private lateinit var layoutConnected: LinearLayout
    private lateinit var tvConnectedName: TextView; lateinit var tvConnectedExtId: TextView; lateinit var btnDisconnect: Button

    private val authListener = FirebaseAuth.AuthStateListener { checkAuthState() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = inflater.inflate(R.layout.fragment_connect, container, false)
        initViews(); setupListeners(); return binding
    }

    override fun onStart() {
        super.onStart()
        auth.addAuthStateListener(authListener)
        checkAuthState()
    }

    override fun onStop() {
        auth.removeAuthStateListener(authListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        checkAuthState()
    }

    private fun initViews() {
        layoutDisconnected = binding.findViewById(R.id.layoutDisconnected); tvStatus = binding.findViewById(R.id.tvStatus)
        layoutGoogleSection = binding.findViewById(R.id.layoutGoogleSection); btnGoogleSignIn = binding.findViewById(R.id.btnGoogleSignIn)
        layoutConnected = binding.findViewById(R.id.layoutConnected); tvConnectedName = binding.findViewById(R.id.tvConnectedName)
        tvConnectedExtId = binding.findViewById(R.id.tvConnectedUid); btnDisconnect = binding.findViewById(R.id.btnDisconnect)
    }

    private fun setupListeners() {
        btnGoogleSignIn.setOnClickListener {
            (activity as? AuthUiHost)?.launchGoogleSignIn()
                ?: Toast.makeText(requireContext(), "Login unavailable", Toast.LENGTH_SHORT).show()
        }
        btnDisconnect.setOnClickListener {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Disconnect?")
                .setMessage("Sign out of Google on this device? The extension will unlink automatically.")
                .setPositiveButton("Yes") { _, _ -> (activity as? MainActivity)?.logoutAndRefreshUi() }
                .setNegativeButton("No", null)
                .show()
        }
    }

    private fun checkAuthState() {
        if (!isAdded || _binding == null) return
        val user = auth.currentUser
        if (user != null && !user.isAnonymous) {
            val name = user.displayName ?: user.email?.substringBefore("@") ?: "Connected"
            showConnectedScreen(name, user.email ?: "")
            try {
                DataBridgeService.start(requireContext())
            } catch (e: Exception) {
                Log.w("ConnectFrag", "DataBridgeService.start failed: ${e.message}")
            }
            (activity as? MainActivity)?.updateConnectionStatus(true)
        } else {
            try {
                DataBridgeService.stop(requireContext())
            } catch (_: Exception) {
            }
            showDisconnectedScreen()
            (activity as? MainActivity)?.updateConnectionStatus(false)
        }
    }

    /** Kept for MainActivity's session-monitor callback — now just re-checks auth state. */
    fun onExternalDisconnect() {
        Log.d("ConnectFrag", "onExternalDisconnect called")
        if (isAdded) checkAuthState()
    }

    private fun showConnectedScreen(name: String, email: String) {
        layoutDisconnected.visibility = View.GONE; layoutConnected.visibility = View.VISIBLE
        tvConnectedName.text = name; tvConnectedExtId.text = email.ifBlank { "Google connected" }
        (activity as? MainActivity)?.updateConnectionStatus(true)
    }
    private fun showDisconnectedScreen() {
        layoutConnected.visibility = View.GONE; layoutDisconnected.visibility = View.VISIBLE
        layoutGoogleSection.visibility = View.VISIBLE
        tvStatus.text = "Disconnected"; tvStatus.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_light))
        (activity as? MainActivity)?.updateConnectionStatus(false)
    }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
