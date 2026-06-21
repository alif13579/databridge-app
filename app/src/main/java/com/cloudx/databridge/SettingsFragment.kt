package com.cloudx.databridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class SettingsFragment : Fragment() {

    private var _binding: View? = null
    private val binding get() = _binding!!

    private lateinit var switchAutoDialer: Switch
    private lateinit var switchAutoDial: Switch
    private lateinit var switchIncomingAlert: Switch
    private lateinit var switchSound: Switch
    private lateinit var switchAutoCopy: Switch
    // ✅ Dark mode toggle removed from settings — now in drawer header
    private lateinit var layoutLogin: LinearLayout
    private lateinit var tvLoginStatus: TextView
    private lateinit var tvLoginSubtext: TextView
    private lateinit var pbLogin: ProgressBar
    private lateinit var btnClearHistory: Button
    private lateinit var pbClearHistory: ProgressBar
    private lateinit var tvAppVersion: TextView

    private val auth = FirebaseAuth.getInstance()
    private lateinit var appPrefs: AppPreferences
    private lateinit var repository: CallRepository
    private val togglePrefs by lazy {
        requireContext().getSharedPreferences("databridge_toggles", Context.MODE_PRIVATE)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = inflater.inflate(R.layout.fragment_settings, container, false)
        return binding
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews()
        initSyncManagerAndPrefs()
        setupListeners()
        updateAuthUI()
    }

    override fun onResume() {
        super.onResume()
        updateAuthUI()
    }

    /** Called from MainActivity after login/logout anywhere in the app. */
    fun onAuthStateChanged() {
        if (isAdded) updateAuthUI()
    }

    private fun initViews() {
        switchAutoDialer     = binding.findViewById(R.id.switchAutoDialer)
        switchAutoDial       = binding.findViewById(R.id.switchAutoDial)
        switchIncomingAlert  = binding.findViewById(R.id.switchIncomingAlert)
        switchSound          = binding.findViewById(R.id.switchSound)
        switchAutoCopy       = binding.findViewById(R.id.switchAutoCopy)
        // switchDarkMode removed — controlled from drawer toggle
        layoutLogin          = binding.findViewById(R.id.layoutLogin)
        tvLoginStatus        = binding.findViewById(R.id.tvLoginStatus)
        tvLoginSubtext       = binding.findViewById(R.id.tvLoginSubtext)
        pbLogin              = binding.findViewById(R.id.pbLogin)
        btnClearHistory      = binding.findViewById(R.id.btnClearHistory)
        pbClearHistory       = binding.findViewById(R.id.pbClearHistory)
        tvAppVersion         = binding.findViewById(R.id.tvAppVersion)
        tvAppVersion.text    = "v2.0 • DataBridge"
        repository           = CallRepository(CallDatabase.getDatabase(requireContext()).callDao())
    }

    private fun initSyncManagerAndPrefs() {
        appPrefs = AppPreferences(requireContext())
    }

    private fun setupListeners() {
        switchAutoDial.setOnCheckedChangeListener(null)
        switchAutoDial.isChecked = togglePrefs.getBoolean("auto_dial", false)
        switchAutoDial.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(requireContext(), "Call permission required", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", requireContext().packageName, null)))
            }
            togglePrefs.edit().putBoolean("auto_dial", isChecked).apply()
        }

        switchAutoDialer.setOnCheckedChangeListener(null)
        switchAutoDialer.isChecked = togglePrefs.getBoolean("auto_open_dialer", false)
        switchAutoDialer.setOnCheckedChangeListener { _, isChecked ->
            togglePrefs.edit().putBoolean("auto_open_dialer", isChecked).apply()
        }

        switchIncomingAlert.setOnCheckedChangeListener(null)
        switchIncomingAlert.isChecked = togglePrefs.getBoolean("incoming_alert", true)
        switchIncomingAlert.setOnCheckedChangeListener { _, isChecked ->
            togglePrefs.edit().putBoolean("incoming_alert", isChecked).apply()
        }

        switchSound.setOnCheckedChangeListener(null)
        switchSound.isChecked = togglePrefs.getBoolean("sound_on_receive", true)
        switchSound.setOnCheckedChangeListener { _, isChecked ->
            togglePrefs.edit().putBoolean("sound_on_receive", isChecked).apply()
        }

        switchAutoCopy.setOnCheckedChangeListener(null)
        switchAutoCopy.isChecked = togglePrefs.getBoolean("auto_copy", false)
        switchAutoCopy.setOnCheckedChangeListener { _, isChecked ->
            togglePrefs.edit().putBoolean("auto_copy", isChecked).apply()
        }

        layoutLogin.setOnClickListener {
            if (AuthManager.isLoggedIn()) {
                showLogoutDialog()
            } else {
                (activity as? AuthUiHost)?.launchGoogleSignIn()
                    ?: Toast.makeText(requireContext(), "Login unavailable", Toast.LENGTH_SHORT).show()
            }
        }

        btnClearHistory.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Clear All History")
                .setMessage("Delete ALL data from Local & Cloud?")
                .setPositiveButton("Clear All") { _, _ -> clearAllHistory() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun updateAuthUI() {
        pbLogin.visibility = View.GONE
        layoutLogin.isEnabled = true
        val user = auth.currentUser
        if (user != null) {
            val displayName = user.displayName ?: user.email ?: "User"
            tvLoginStatus.text = "Logged in as $displayName"
            tvLoginSubtext.text = "Cloud sync active"
            tvLoginSubtext.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_text_accent))
            tvLoginStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_text_primary))
        } else {
            tvLoginStatus.text = "Login / Register"
            tvLoginSubtext.text = "Sign in to save data to cloud"
            tvLoginSubtext.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_text_secondary))
            tvLoginStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.theme_text_primary))
        }
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Logout?")
            .setMessage("Cloud data stays safe on your account.")
            .setPositiveButton("Logout") { _, _ -> performLogout() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performLogout() {
        (activity as? MainActivity)?.logoutAndRefreshUi()
        view?.postDelayed({
            if (isAdded) {
                updateAuthUI()
                (activity as? AuthUiHost)?.refreshAuthUi(forceReload = true)
            }
        }, 400)
    }

    private fun clearAllHistory() {
        pbClearHistory.visibility = View.VISIBLE
        btnClearHistory.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val user = auth.currentUser
                val extId = appPrefs.getCurrentExtensionId()
                if (user != null) {
                    FirebaseDatabase.getInstance()
                        .getReference("container/container_${user.uid}/records")
                        .removeValue()
                        .await()
                } else if (!extId.isNullOrEmpty()) {
                    FirebaseDatabase.getInstance()
                        .getReference("sessions/$extId/records")
                        .removeValue()
                        .await()
                }
                repository.deleteAllCalls()
                Toast.makeText(requireContext(), "Cleared", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                pbClearHistory.visibility = View.GONE
                btnClearHistory.isEnabled = true
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}