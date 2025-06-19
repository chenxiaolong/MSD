/*
 * SPDX-FileCopyrightText: 2024-2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.msd.settings

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Parcelable
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentResultListener
import androidx.fragment.app.clearFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.get
import androidx.preference.size
import androidx.recyclerview.widget.RecyclerView
import com.chiller3.msd.BuildConfig
import com.chiller3.msd.Preferences
import com.chiller3.msd.R
import com.chiller3.msd.dialog.DeviceDialogFragment
import com.chiller3.msd.dialog.ImageSizeDialogFragment
import com.chiller3.msd.dialog.MessageDialogFragment
import com.chiller3.msd.extension.DOCUMENTSUI_AUTHORITY
import com.chiller3.msd.extension.formattedString
import com.chiller3.msd.view.LongClickablePreference
import com.chiller3.msd.view.OnPreferenceLongClickListener
import com.chiller3.msd.view.SplitSwitchPreference
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

class SettingsFragment : PreferenceFragmentCompat(), FragmentResultListener,
    Preference.OnPreferenceClickListener, OnPreferenceLongClickListener,
    Preference.OnPreferenceChangeListener {
    companion object {
        private const val KEY_PARTIAL_STATE = "partial_state"
    }

    @Parcelize
    private sealed interface PartialState : Parcelable {
        @Parcelize
        data class ExistingImageTypeSelected(val deviceType: DeviceType) : PartialState

        @Parcelize
        data class NewImageSizeSelected(val size: Long) : PartialState
    }

    private var partialState: PartialState? = null

    private val viewModel: SettingsViewModel by viewModels()

    private lateinit var prefs: Preferences
    private lateinit var categoryDevices: PreferenceCategory
    private lateinit var categoryDebug: PreferenceCategory
    private lateinit var prefAddDevice: Preference
    private lateinit var prefActiveFunctions: Preference
    private lateinit var prefApplySettings: Preference
    private lateinit var prefVersion: LongClickablePreference
    private lateinit var prefOpenLogDir: Preference

    private val requestSafOpenImage =
        registerForActivityResult(OpenPersistentDocument()) { uri ->
            val state = partialState as PartialState.ExistingImageTypeSelected
            partialState = null

            uri?.let {
                viewModel.addDevice(it, state.deviceType)
            }
        }
    private val requestSafCreateImage =
        registerForActivityResult(CreatePersistentDocument("*/*")) { uri ->
            val state = partialState as PartialState.NewImageSizeSelected
            partialState = null

            uri?.let {
                viewModel.createDevice(it, state.size, SettingsViewModel.OpenMode.CREATE)
            }
        }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView {
        val view = super.onCreateRecyclerView(inflater, parent, savedInstanceState)

        view.clipToPadding = false

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )

            // This is a little bit ugly in landscape mode because the divider lines for categories
            // extend into the inset area. However, it's worth applying the left/right padding here
            // anyway because it allows the inset area to be used for scrolling instead of just
            // being a useless dead zone.
            v.updatePadding(
                bottom = insets.bottom,
                left = insets.left,
                right = insets.right,
            )

            WindowInsetsCompat.CONSUMED
        }

        return view
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences_root, rootKey)

        if (savedInstanceState != null) {
            partialState = BundleCompat.getParcelable(
                savedInstanceState,
                KEY_PARTIAL_STATE,
                PartialState::class.java,
            )
        }

        val context = requireContext()

        prefs = Preferences(context)

        categoryDevices = findPreference(Preferences.CATEGORY_DEVICES)!!
        categoryDebug = findPreference(Preferences.CATEGORY_DEBUG)!!

        prefAddDevice = findPreference(Preferences.PREF_ADD_DEVICE)!!
        prefAddDevice.onPreferenceClickListener = this

        prefActiveFunctions = findPreference(Preferences.PREF_ACTIVE_FUNCTIONS)!!
        prefActiveFunctions.onPreferenceClickListener = this

        prefApplySettings = findPreference(Preferences.PREF_APPLY_SETTINGS)!!
        prefApplySettings.onPreferenceClickListener = this

        prefVersion = findPreference(Preferences.PREF_VERSION)!!
        prefVersion.onPreferenceClickListener = this
        prefVersion.onPreferenceLongClickListener = this

        prefOpenLogDir = findPreference(Preferences.PREF_OPEN_LOG_DIR)!!
        prefOpenLogDir.onPreferenceClickListener = this

        refreshVersion()
        refreshDebugPrefs()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.alerts.collect {
                    it.firstOrNull()?.let { alert ->
                        onAlert(alert)
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.canAct.collect {
                    updateUiLockState(it)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.devices.collect {
                    addDevicePreferences(it)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.activeFunctions.collect {
                    updateActiveFunctions(it)
                }
            }
        }

        parentFragmentManager.setFragmentResultListener(DeviceDialogFragment.TAG, this, this)
        parentFragmentManager.setFragmentResultListener(ImageSizeDialogFragment.TAG, this, this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putParcelable(KEY_PARTIAL_STATE, partialState)
    }

    override fun onFragmentResult(requestKey: String, result: Bundle) {
        clearFragmentResult(requestKey)

        when (requestKey) {
            DeviceDialogFragment.TAG -> {
                val action = BundleCompat.getParcelable(
                    result,
                    DeviceDialogFragment.RESULT_ACTION,
                    DeviceDialogFragment.Action::class.java,
                )

                when (action) {
                    is DeviceDialogFragment.AddDevice -> {
                        partialState = PartialState.ExistingImageTypeSelected(action.deviceType)

                        // AOSP does not have any MIME types for .iso or .img files.
                        // See: frameworks/base/mime/java-res/android.mime.types
                        requestSafOpenImage.launch(arrayOf("*/*"))
                    }
                    is DeviceDialogFragment.ChangeDevice -> {
                        viewModel.addDevice(action.uri, action.deviceType)
                    }
                    DeviceDialogFragment.CreateDevice -> {
                        ImageSizeDialogFragment.newForCreate().show(
                            parentFragmentManager.beginTransaction(), ImageSizeDialogFragment.TAG
                        )
                    }
                    is DeviceDialogFragment.ResizeDevice -> {
                        ImageSizeDialogFragment.newForResize(action.uri, action.existingSize).show(
                            parentFragmentManager.beginTransaction(), ImageSizeDialogFragment.TAG
                        )
                    }
                    is DeviceDialogFragment.RemoveDevice -> {
                        viewModel.removeDevice(action.uri)
                    }
                    // Cancelled.
                    null -> {}
                }
            }
            ImageSizeDialogFragment.TAG -> {
                val action = BundleCompat.getParcelable(
                    result,
                    ImageSizeDialogFragment.RESULT_ACTION,
                    ImageSizeDialogFragment.Action::class.java,
                )

                when (action) {
                    is ImageSizeDialogFragment.CreateImage -> {
                        partialState = PartialState.NewImageSizeSelected(action.size)
                        requestSafCreateImage.launch("disk.img")
                    }
                    is ImageSizeDialogFragment.ResizeImage -> {
                        viewModel.createDevice(
                            action.uri,
                            action.size,
                            SettingsViewModel.OpenMode.RESIZE,
                        )
                    }
                    // Cancelled.
                    null -> {}
                }
            }
        }
    }

    private fun refreshVersion() {
        val suffix = if (prefs.isDebugMode) {
            "+debugmode"
        } else {
            ""
        }
        prefVersion.summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}${suffix})"
    }

    private fun refreshDebugPrefs() {
        categoryDebug.isVisible = prefs.isDebugMode
    }

    private fun updateActiveFunctions(functions: Map<String, String>) {
        if (functions.isEmpty()) {
            prefActiveFunctions.summary = getString(R.string.pref_active_functions_desc_none)
        } else {
            prefActiveFunctions.summary = buildString {
                var first = true

                for ((config, function) in functions) {
                    if (first) {
                        first = false
                    } else {
                        append('\n')
                    }
                    append(config)
                    append(": ")
                    append(function)
                }
            }
        }
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        when {
            preference === prefAddDevice -> {
                DeviceDialogFragment.newInstance(null)
                    .show(parentFragmentManager.beginTransaction(), DeviceDialogFragment.TAG)
                return true
            }
            preference.key.startsWith(Preferences.PREF_DEVICE_PREFIX) -> {
                val index = preference.key.removePrefix(Preferences.PREF_DEVICE_PREFIX).toInt()
                val device = viewModel.devices.value[index]

                DeviceDialogFragment.newInstance(device)
                    .show(parentFragmentManager.beginTransaction(), DeviceDialogFragment.TAG)
                return true
            }
            preference === prefActiveFunctions -> {
                viewModel.refreshUsbState()
                return true
            }
            preference === prefApplySettings -> {
                viewModel.setMassStorage()
                return true
            }
            preference === prefVersion -> {
                val uri = BuildConfig.PROJECT_URL_AT_COMMIT.toUri()
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                return true
            }
            preference === prefOpenLogDir -> {
                val externalDir = Environment.getExternalStorageDirectory()
                val filesDir = requireContext().getExternalFilesDir(null)!!
                val relPath = filesDir.relativeTo(externalDir)
                val uri = DocumentsContract.buildDocumentUri(
                    DOCUMENTSUI_AUTHORITY, "primary:$relPath")
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "vnd.android.document/directory")
                }
                startActivity(intent)
                return true
            }
        }

        return false
    }

    override fun onPreferenceLongClick(preference: Preference): Boolean {
        when {
            preference === prefVersion -> {
                prefs.isDebugMode = !prefs.isDebugMode
                refreshVersion()
                refreshDebugPrefs()
                return true
            }
        }

        return false
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        when {
            preference.key.startsWith(Preferences.PREF_DEVICE_PREFIX) -> {
                val index = preference.key.removePrefix(Preferences.PREF_DEVICE_PREFIX).toInt()
                viewModel.toggleDevice(viewModel.devices.value[index].uri, newValue as Boolean)
                // Intentionally not returning true since the view model will refresh the devices.
            }
        }

        return false
    }

    private fun updateUiLockState(canAct: Boolean) {
        for (i in 0 until categoryDevices.size) {
            categoryDevices[i].isEnabled = canAct
        }

        prefActiveFunctions.isEnabled = canAct
        prefApplySettings.isEnabled = canAct
    }

    private fun addDevicePreferences(devices: List<UiDeviceInfo>) {
        val context = requireContext()
        val canAct = viewModel.canAct.value

        for (i in (0 until categoryDevices.size).reversed()) {
            val p = categoryDevices[i]

            if (p.key.startsWith(Preferences.PREF_DEVICE_PREFIX)) {
                categoryDevices.removePreference(p)
            }
        }

        for ((i, device) in devices.withIndex()) {
            val p = SplitSwitchPreference(context).apply {
                key = Preferences.PREF_DEVICE_PREFIX + i
                isPersistent = false
                title = when (device.type) {
                    DeviceType.CDROM -> getString(R.string.pref_device_name_cdrom)
                    DeviceType.DISK_RO -> getString(R.string.pref_device_name_disk_ro)
                    DeviceType.DISK_RW -> getString(R.string.pref_device_name_disk_rw)
                }
                summary = device.localPath ?: device.uri.formattedString
                isIconSpaceReserved = false
                isPersistent = false
                isChecked = device.enabled
                isEnabled = canAct
                onPreferenceClickListener = this@SettingsFragment
                onPreferenceChangeListener = this@SettingsFragment
            }

            categoryDevices.addPreference(p)
        }
    }

    private fun onAlert(alert: Alert) {
        val msg = when (alert) {
            is Alert.QueryStateFailure -> getString(R.string.alert_query_state_failure)
            is Alert.ApplyStateFailure -> getString(R.string.alert_apply_state_failure)
            is Alert.ReapplyRequired -> getString(R.string.alert_reapply_required)
            is Alert.NotLocalFile -> getString(R.string.alert_not_local_file)
            is Alert.CreateImageFailure -> getString(R.string.alert_create_image_failure)
            is Alert.ResizeImageFailure -> getString(R.string.alert_resize_image_failure)
        }

        val details = when (alert) {
            is Alert.QueryStateFailure -> alert.error
            is Alert.ApplyStateFailure -> alert.error
            is Alert.ReapplyRequired -> null
            is Alert.NotLocalFile -> getString(R.string.alert_not_local_file_details)
            is Alert.CreateImageFailure -> alert.error
            is Alert.ResizeImageFailure -> alert.error
        }

        // Give users a chance to read the message. LENGTH_LONG is only 2750ms.
        Snackbar.make(requireView(), msg, 5000)
            .apply {
                if (details != null) {
                    setAction(R.string.action_details) {
                        MessageDialogFragment.newInstance("Error details", details)
                            .show(parentFragmentManager.beginTransaction(),
                                MessageDialogFragment.TAG)
                    }
                }
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (event != DISMISS_EVENT_CONSECUTIVE) {
                        viewModel.acknowledgeFirstAlert()
                    }
                }
            })
            .show()
    }
}
