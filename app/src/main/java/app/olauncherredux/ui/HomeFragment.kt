package app.olauncherredux.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.Paint
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.children
import androidx.core.view.size
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import app.olauncherredux.MainViewModel
import app.olauncherredux.R
import app.olauncherredux.data.AppModel
import app.olauncherredux.data.Constants
import app.olauncherredux.data.Constants.Action
import app.olauncherredux.data.Constants.AppDrawerFlag
import app.olauncherredux.data.Prefs
import app.olauncherredux.databinding.FragmentHomeBinding
import app.olauncherredux.helper.*
import app.olauncherredux.listener.OnSwipeTouchListener
import app.olauncherredux.listener.ViewSwipeTouchListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class HomeFragment : Fragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager
    private lateinit var vibrator: Vibrator

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var expandedGroupIndex: Int = -1
    private var groupAnchorY: Int = -1
    private var isFirstRun = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)

        val view = binding.root
        prefs = Prefs(requireContext())

        if (prefs.firstSettingsOpen()) {
            isFirstRun = true
            binding.firstRunTips.visibility = View.VISIBLE
            binding.setDefaultLauncher.visibility = View.GONE
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        deviceManager = context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        vibrator = context?.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        initObservers()

        initSwipeTouchListener()
        initClickListeners()

        if (isFirstRun) {
            isFirstRun = false
            showWallpaperDialog()
        }
    }

    private fun showWallpaperDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.wallpaper)
            .setMessage(R.string.wallpaper_prompt_message)
            .setPositiveButton(R.string.set_wallpaper) { _, _ -> applyWallpaper() }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    private fun applyWallpaper() {
        val context = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = setBundledWallpaper(context)
            withContext(Dispatchers.Main) {
                showToastShort(context, getString(if (ok) R.string.wallpaper_set else R.string.wallpaper_failed))
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (prefs.showStatusBar) showStatusBar(requireActivity()) else hideStatusBar(requireActivity())

        binding.clock.textSize = prefs.textSize * 2.5f
        binding.date.textSize = prefs.textSize.toFloat()

    }

    override fun onResume() {
        super.onResume()

        if (expandedGroupIndex >= 0) {
            collapseGroup()
        }

        if (binding.firstRunTips.visibility == View.GONE) {
            binding.setDefaultLauncher.visibility =
                if (isOlauncherDefault(requireContext())) View.GONE else View.VISIBLE
        }
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.clock -> {
                when (val action = prefs.clickClockAction) {
                    Action.OpenApp -> openClickClockApp()
                    else -> handleOtherAction(action)
                }
            }
            R.id.date -> {
                when (val action = prefs.clickDateAction) {
                    Action.OpenApp -> openClickDateApp()
                    else -> handleOtherAction(action)
                }
            }
            R.id.setDefaultLauncher -> viewModel.resetDefaultLauncherApp(requireContext())
            else -> {
                try {
                    val index = view.id.toString().toInt()
                    if (expandedGroupIndex >= 0) {
                        val appCount = prefs.getGroupAppCount(expandedGroupIndex)
                        if (index < appCount) {
                            val appModel = prefs.getGroupAppModel(expandedGroupIndex, index)
                            if (appModel.appPackage.isNotEmpty()) launchApp(appModel)
                        } else {
                            showAppList(AppDrawerFlag.SetGroupApp, true, expandedGroupIndex, index)
                        }
                    } else {
                        homeAppClicked(index, view)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onLongClick(view: View): Boolean {
        if (prefs.homeLocked) return true

        val n = view.id

        if (expandedGroupIndex >= 0) {
            val appCount = prefs.getGroupAppCount(expandedGroupIndex)
            if (n < appCount) {
                showGroupAppOptionsDialog(expandedGroupIndex, n)
            } else {
                showAppList(AppDrawerFlag.SetGroupApp, true, expandedGroupIndex, n)
            }
            return true
        }

        showSlotOptionsDialog(n)
        return true
    }

    private fun homeAppClicked(location: Int, view: View? = null) {
        if (prefs.isHomeSlotGroup(location)) {
            if (view != null) {
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                groupAnchorY = loc[1] + view.height / 2
            }
            expandGroup(location)
        } else {
            if (prefs.getAppName(location).isEmpty()) {
                // Empty slot: open the app picker directly, unless the home screen is locked
                if (prefs.homeLocked) showLongPressToast()
                else showAppList(AppDrawerFlag.SetHomeApp, false, location)
            } else {
                launchApp(prefs.getHomeAppModel(location))
            }
        }
    }

    private fun expandGroup(groupIndex: Int) {
        expandedGroupIndex = groupIndex
        rebuildHomeAppsLayout(true)
    }

    private fun collapseGroup() {
        expandedGroupIndex = -1
        groupAnchorY = -1
        rebuildHomeAppsLayout(true)
    }

    private fun measureTextWidth(text: String): Float {
        val paint = Paint()
        paint.textSize = prefs.textSize * resources.displayMetrics.scaledDensity
        return paint.measureText(text)
    }

    private fun rebuildHomeAppsLayout(animate: Boolean = false) {
        binding.homeAppsLayout.removeAllViews()
        val alignment = prefs.homeAlignment.value()

        if (expandedGroupIndex >= 0) {
            binding.homeAppsLayout.gravity = prefs.homeAlignment.value() or Gravity.TOP

            val items = mutableListOf<Pair<Int, String>>()
            val count = prefs.getGroupAppCount(expandedGroupIndex)
            for (j in 0 until count) {
                items.add(j to prefs.getGroupAppModel(expandedGroupIndex, j).name)
            }
            items.sortBy { measureTextWidth(it.second) }
            for ((id, label) in items) {
                val view = createAppTextView(id, label, alignment)
                binding.homeAppsLayout.addView(view)
            }
            if (count < Constants.MAX_GROUP_APPS) {
                val addView = createAppTextView(count, "", alignment)
                addView.hint = "+"
                binding.homeAppsLayout.addView(addView)
            }

            if (groupAnchorY > 0) {
                binding.homeAppsLayout.post {
                    val layoutLoc = IntArray(2)
                    binding.homeAppsLayout.getLocationOnScreen(layoutLoc)
                    val contentHeight = binding.homeAppsLayout.children.sumOf { it.height }
                    val targetTop = groupAnchorY - contentHeight / 2 - layoutLoc[1]
                    val maxPad = binding.homeAppsLayout.height - contentHeight - binding.homeAppsLayout.paddingBottom
                    val pad = targetTop.coerceIn(0, maxPad.coerceAtLeast(0))
                    binding.homeAppsLayout.setPadding(
                        binding.homeAppsLayout.paddingLeft,
                        pad,
                        binding.homeAppsLayout.paddingRight,
                        binding.homeAppsLayout.paddingBottom
                    )
                }
            }
        } else {
            val onBottom = prefs.homeAlignmentBottom
            val verticalAlignment = if (onBottom) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
            binding.homeAppsLayout.gravity = prefs.homeAlignment.value() or verticalAlignment
            val defaultTopPad = (112 * resources.displayMetrics.density).toInt()
            binding.homeAppsLayout.setPadding(
                binding.homeAppsLayout.paddingLeft,
                defaultTopPad,
                binding.homeAppsLayout.paddingRight,
                binding.homeAppsLayout.paddingBottom
            )

            val items = mutableListOf<Triple<Int, String, Boolean>>()
            val count = prefs.homeAppsNum
            for (i in 0 until count) {
                val isGroup = prefs.isHomeSlotGroup(i)
                val label = if (isGroup) {
                    val name = prefs.getGroupName(i)
                    if (name.isNotEmpty()) "$name ▸" else ""
                } else {
                    prefs.getHomeAppModel(i).name
                }
                items.add(Triple(i, label, isGroup))
            }
            items.sortBy { measureTextWidth(it.second) }
            for ((id, label, isGroup) in items) {
                val view = createAppTextView(id, label, alignment)
                if (isGroup) view.alpha = 0.7f
                binding.homeAppsLayout.addView(view)
            }
        }

        if (animate) {
            val animation = AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_anim_from_bottom)
            binding.homeAppsLayout.layoutAnimation = animation
            binding.homeAppsLayout.scheduleLayoutAnimation()
        }
    }

    private fun createAppTextView(id: Int, label: String, alignment: Int): TextView {
        val view = layoutInflater.inflate(R.layout.home_app_button, null) as TextView
        view.apply {
            textSize = prefs.textSize.toFloat()
            this.id = id
            text = label
            setOnTouchListener(getHomeAppsGestureListener(context, this))
            if (!prefs.extendHomeAppsArea) {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            gravity = alignment
        }
        return view
    }

    private fun showSlotOptionsDialog(slotIndex: Int) {
        val isGroup = prefs.isHomeSlotGroup(slotIndex)
        val options = if (isGroup) {
            arrayOf(
                getString(R.string.rename_group),
                getString(R.string.edit_group),
                getString(R.string.set_app),
                getString(R.string.reset)
            )
        } else {
            val name = prefs.getHomeAppModel(slotIndex).appLabel
            if (name.isNotEmpty()) {
                arrayOf(getString(R.string.set_app), getString(R.string.set_group), getString(R.string.reset))
            } else {
                arrayOf(getString(R.string.set_app), getString(R.string.set_group))
            }
        }

        AlertDialog.Builder(requireContext())
            .setItems(options) { _, which ->
                val chosen = options[which]
                when (chosen) {
                    getString(R.string.set_app) -> {
                        if (isGroup) prefs.clearGroup(slotIndex)
                        val name = prefs.getHomeAppModel(slotIndex).appLabel
                        showAppList(AppDrawerFlag.SetHomeApp, name.isNotEmpty(), slotIndex)
                    }
                    getString(R.string.set_group) -> {
                        prefs.setHomeSlotGroup(slotIndex, true)
                        if (prefs.getGroupName(slotIndex).isEmpty()) {
                            showRenameGroupDialog(slotIndex, true)
                        } else {
                            expandGroup(slotIndex)
                        }
                    }
                    getString(R.string.edit_group) -> {
                        expandGroup(slotIndex)
                    }
                    getString(R.string.rename_group) -> {
                        showRenameGroupDialog(slotIndex, false)
                    }
                    getString(R.string.reset) -> {
                        if (isGroup) prefs.clearGroup(slotIndex)
                        prefs.setHomeAppModel(slotIndex, AppModel("", null, "", "", android.os.Process.myUserHandle(), ""))
                        rebuildHomeAppsLayout()
                    }
                }
            }
            .show()
    }

    private fun showRenameGroupDialog(slotIndex: Int, expandAfter: Boolean) {
        val editText = EditText(requireContext())
        editText.setText(prefs.getGroupName(slotIndex))
        editText.hint = getString(R.string.group_name)
        AlertDialog.Builder(requireContext())
            .setView(editText)
            .setPositiveButton(getString(R.string.okay)) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    prefs.setGroupName(slotIndex, name)
                }
                if (expandAfter) {
                    expandGroup(slotIndex)
                } else {
                    rebuildHomeAppsLayout()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showGroupAppOptionsDialog(groupIndex: Int, appIndex: Int) {
        val options = arrayOf(getString(R.string.replace), getString(R.string.remove))
        AlertDialog.Builder(requireContext())
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAppList(AppDrawerFlag.SetGroupApp, true, groupIndex, appIndex)
                    1 -> {
                        prefs.removeGroupApp(groupIndex, appIndex)
                        rebuildHomeAppsLayout(true)
                    }
                }
            }
            .show()
    }

    private fun initSwipeTouchListener() {
        val context = requireContext()
        binding.touchArea.setOnTouchListener(getHomeScreenGestureListener(context))
    }

    private fun initClickListeners() {
        binding.clock.setOnClickListener(this)
        binding.date.setOnClickListener(this)
        binding.setDefaultLauncher.setOnClickListener(this)
    }

    private fun initObservers() {
        with(viewModel) {
            clockAlignment.observe(viewLifecycleOwner) { gravity ->
                binding.dateTimeLayout.gravity = gravity.value()
            }
            homeAppsAlignment.observe(viewLifecycleOwner) { (gravity, onBottom) ->
                val horizontalAlignment = if (onBottom) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
                binding.homeAppsLayout.gravity = gravity.value() or horizontalAlignment

                binding.homeAppsLayout.children.forEach { view ->
                    (view as TextView).gravity = gravity.value()
                }
            }
            homeAppsCount.observe(viewLifecycleOwner) {
                updateAppCount(it)
            }
            showTime.observe(viewLifecycleOwner) {
                binding.clock.visibility = if (it) View.VISIBLE else View.GONE
            }
            showDate.observe(viewLifecycleOwner) {
                binding.date.visibility = if (it) View.VISIBLE else View.GONE
            }
        }
    }

    private fun launchApp(appModel: AppModel) {
        viewModel.selectedApp(appModel, AppDrawerFlag.LaunchApp)
    }

    private fun showAppList(flag: AppDrawerFlag, showHiddenApps: Boolean = false, n: Int = 0, groupAppIndex: Int = -1) {
        viewModel.getAppList(showHiddenApps)
        lifecycleScope.launch {
            try {
                findNavController().navigate(
                    R.id.action_mainFragment_to_appListFragment,
                    bundleOf("flag" to flag.toString(), "n" to n, "groupAppIndex" to groupAppIndex)
                )
            } catch (e: Exception) {
                findNavController().navigate(
                    R.id.appListFragment,
                    bundleOf("flag" to flag.toString(), "n" to n, "groupAppIndex" to groupAppIndex)
                )
                e.printStackTrace()
            }
        }
    }

    private fun openSwipeRightApp() {
        if (prefs.appSwipeRight.appPackage.isNotEmpty())
            launchApp(prefs.appSwipeRight)
        else openDialerApp(requireContext())
    }

    private fun openSwipeDownApp() {
        if (prefs.appSwipeDown.appPackage.isNotEmpty())
            launchApp(prefs.appSwipeDown)
        else openDialerApp(requireContext())
    }

    private fun openSwipeUpApp() {
        if (prefs.appSwipeUp.appPackage.isNotEmpty())
            launchApp(prefs.appSwipeUp)
        else showAppList(AppDrawerFlag.LaunchApp)
    }

    private fun openClickClockApp() {
        if (prefs.appClickClock.appPackage.isNotEmpty())
            launchApp(prefs.appClickClock)
        else openAlarmApp(requireContext())
    }

    private fun openClickDateApp() {
        if (prefs.appClickDate.appPackage.isNotEmpty())
            launchApp(prefs.appClickDate)
        else openCalendar(requireContext())
    }

    private fun openSwipeLeftApp() {
        if (prefs.appSwipeLeft.appPackage.isNotEmpty())
            launchApp(prefs.appSwipeLeft)
        else openCameraApp(requireContext())
    }

    private fun openDoubleTapApp() {
        if (prefs.appDoubleTap.appPackage.isNotEmpty())
            launchApp(prefs.appDoubleTap)
        else openCameraApp(requireContext())
    }

    @SuppressLint("NewApi")
    private fun handleOtherAction(action: Action) {
        when(action) {
            Action.ShowNotification -> expandNotificationDrawer(requireContext())
            Action.LockScreen -> lockPhone()
            Action.ShowAppList -> showAppList(AppDrawerFlag.LaunchApp)
            Action.OpenApp -> {}
            Action.OpenQuickSettings -> expandQuickSettings(requireContext())
            Action.ShowRecents -> initActionService(requireContext())?.showRecents()
            Action.Disabled -> {}
        }
    }

    private fun lockPhone() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val actionService = ActionService.instance()
            if (actionService != null) {
                actionService.lockScreen()
            } else {
                openAccessibilitySettings(requireContext())
            }
        } else {
            requireActivity().runOnUiThread {
                try {
                    deviceManager.lockNow()
                } catch (e: SecurityException) {
                    showToastLong(requireContext(), "App does not have the permission to lock the device")
                } catch (e: Exception) {
                    showToastLong(requireContext(), "Olauncher failed to lock device.\nPlease check your app settings.")
                    prefs.lockModeOn = false
                }
            }
        }
    }

    private fun showLongPressToast() = showToastShort(requireContext(), "Long press to select app")

    private fun textOnClick(view: View) = onClick(view)

    private fun textOnLongClick(view: View) = onLongClick(view)

    private fun getHomeScreenGestureListener(context: Context): View.OnTouchListener {
        return object : OnSwipeTouchListener(context) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                when(val action = prefs.swipeLeftAction) {
                    Action.OpenApp -> openSwipeLeftApp()
                    else -> handleOtherAction(action)
                }
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                when(val action = prefs.swipeRightAction) {
                    Action.OpenApp -> openSwipeRightApp()
                    else -> handleOtherAction(action)
                }
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                when(val action = prefs.swipeUpAction) {
                    Action.OpenApp -> openSwipeUpApp()
                    else -> handleOtherAction(action)
                }
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                when(val action = prefs.swipeDownAction) {
                    Action.OpenApp -> openSwipeDownApp()
                    else -> handleOtherAction(action)
                }
            }

            override fun onLongClick() {
                super.onLongClick()
                if (expandedGroupIndex >= 0) {
                    collapseGroup()
                    return
                }
                try {
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                } catch (e: java.lang.Exception) {
                }
            }

            override fun onDoubleClick() {
                super.onDoubleClick()
                when(val action = prefs.doubleTapAction) {
                    Action.OpenApp -> openDoubleTapApp()
                    else -> handleOtherAction(action)
                }
            }

            override fun onTapUp() {
                super.onTapUp()
                if (expandedGroupIndex >= 0) {
                    collapseGroup()
                }
            }
        }
    }

    private fun getHomeAppsGestureListener(context: Context, view: View): View.OnTouchListener {
        return object : ViewSwipeTouchListener(context, view) {
            override fun onLongClick(view: View) {
                super.onLongClick(view)
                textOnLongClick(view)
            }

            override fun onClick(view: View) {
                super.onClick(view)
                textOnClick(view)
            }

            override fun onSwipeLeft() {
                super.onSwipeLeft()
                when(val action = prefs.swipeLeftAction) {
                    Action.OpenApp -> openSwipeLeftApp()
                    else -> handleOtherAction(action)
                }
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                when(val action = prefs.swipeRightAction) {
                    Action.OpenApp -> openSwipeRightApp()
                    else -> handleOtherAction(action)
                }
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                when(val action = prefs.swipeUpAction) {
                    Action.OpenApp -> openSwipeUpApp()
                    else -> handleOtherAction(action)
                }
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                when(val action = prefs.swipeDownAction) {
                    Action.OpenApp -> openSwipeDownApp()
                    else -> handleOtherAction(action)
                }
            }
        }
    }

    private fun updateAppCount(newAppsNum: Int) {
        if (expandedGroupIndex >= 0) return
        rebuildHomeAppsLayout()
    }
}
