package com.haseeb.recorder.ui.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.listitem.ListItemLayout
import com.haseeb.recorder.R
import com.haseeb.recorder.databinding.ActivityAboutBinding
import com.haseeb.recorder.databinding.ItemAboutBinding
import com.haseeb.recorder.util.LocaleHelper
import com.haseeb.recorder.util.applyBottomInsets
import com.haseeb.recorder.util.applySystemBarInsets
import com.haseeb.recorder.util.applyTopInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/*
 * Manages the About screen displaying application details, contributors, source code, and libraries.
 * Handles update checks from GitHub releases and renders items with Material 3 Expressive shapes.
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding
    private var isCheckingUpdate = false

    /*
     * Wraps the base context with the user-selected locale configuration.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    /*
     * Initializes the activity layout binding, applies window insets, and configures the list.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.applyTopInsets()
        binding.appBarLayout.applySystemBarInsets()
        binding.recyclerView.applyBottomInsets()

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecycler()
    }

    /*
     * Prepares the dataset for the about screen sections and attaches the adapter to RecyclerView.
     */
    private fun setupRecycler() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
        }
        val currentVersion = getString(R.string.AboutActivity_version_format, versionName)

        val items = listOf(
            AboutItem(
                type = ItemType.APP_INFO,
                titleRes = R.string.AboutActivity_app_title,
                rawSubtitle = currentVersion,
                descriptionRes = R.string.AboutActivity_app_description,
                tags = listOf(
                    getString(R.string.AboutActivity_tag_open_source),
                    getString(R.string.AboutActivity_tag_m3)
                ),
                githubUrl = "https://github.com/muhammadhaseebiqbal-dev/Screen-Recorder",
                icon = Icon.Mipmap(R.mipmap.ic_launcher)
            ),
            AboutItem(
                type = ItemType.CATEGORY_HEADER,
                titleRes = R.string.AboutActivity_cat_maintainer,
                subtitleRes = R.string.AboutActivity_cat_maintainer_sub
            ),
            AboutItem(
                type = ItemType.CONTRIBUTOR,
                titleRes = R.string.AboutActivity_maintainer_name,
                subtitleRes = R.string.AboutActivity_maintainer_role,
                descriptionRes = R.string.AboutActivity_maintainer_desc,
                tags = listOf(
                    getString(R.string.AboutActivity_maintainer_tag_lead),
                    getString(R.string.AboutActivity_maintainer_tag_creator)
                ),
                primaryUrl = "https://github.com/muhammadhaseebiqbal-dev",
                githubUrl = "https://github.com/muhammadhaseebiqbal-dev",
                emailUrl = "mailto:muhammadhaseebiqbal@proton.me",
                coffeeUrl = "https://www.patreon.com/MuhammadHaseebIqbal/posts/buy-me-coffee-162409817?utm_medium=clipboard_copy&utm_source=copyLink&utm_campaign=postshare_creator&utm_content=join_link",
                icon = Icon.Url("https://github.com/muhammadhaseebiqbal-dev.png")
            ),
            AboutItem(
                type = ItemType.CATEGORY_HEADER,
                titleRes = R.string.AboutActivity_cat_contributors,
                subtitleRes = R.string.AboutActivity_cat_contributors_sub
            ),
            AboutItem(
                type = ItemType.CONTRIBUTOR,
                titleRes = R.string.AboutActivity_contributor_ameer_name,
                subtitleRes = R.string.AboutActivity_contributor_ameer_role,
                descriptionRes = R.string.AboutActivity_contributor_ameer_desc,
                tags = listOf(
                    getString(R.string.AboutActivity_contributor_tag_architect),
                    getString(R.string.AboutActivity_contributor_tag_lead_contributor),
                    getString(R.string.AboutActivity_contributor_tag_ui),
                    getString(R.string.AboutActivity_contributor_tag_system)
                ),
                primaryUrl = "https://github.com/ameermuawiya",
                githubUrl = "https://github.com/ameermuawiya",
                telegramUrl = "https://t.me/ameermuawiya",
                coffeeUrl = "https://www.patreon.com/ameermuawiyapk/posts/buy-me-coffee-168910489",
                icon = Icon.Url("https://github.com/ameermuawiya.png")
            ),
            AboutItem(
                type = ItemType.CATEGORY_HEADER,
                titleRes = R.string.AboutActivity_cat_libraries,
                subtitleRes = R.string.AboutActivity_cat_libraries_sub
            ),
            AboutItem(
                type = ItemType.LIBRARY,
                titleRes = R.string.AboutActivity_lib_material,
                subtitleRes = R.string.AboutActivity_lib_material_desc,
                icon = Icon.Drawable(R.drawable.ic_license)
            ),
            AboutItem(
                type = ItemType.LIBRARY,
                titleRes = R.string.AboutActivity_lib_jetpack,
                subtitleRes = R.string.AboutActivity_lib_jetpack_desc,
                icon = Icon.Drawable(R.drawable.ic_license)
            ),
            AboutItem(
                type = ItemType.LIBRARY,
                titleRes = R.string.AboutActivity_lib_coroutines,
                subtitleRes = R.string.AboutActivity_lib_coroutines_desc,
                icon = Icon.Drawable(R.drawable.ic_license)
            ),
            AboutItem(
                type = ItemType.LIBRARY,
                titleRes = R.string.AboutActivity_lib_shizuku,
                subtitleRes = R.string.AboutActivity_lib_shizuku_desc,
                icon = Icon.Drawable(R.drawable.ic_license)
            ),
            AboutItem(
                type = ItemType.LIBRARY,
                titleRes = R.string.AboutActivity_lib_glide,
                subtitleRes = R.string.AboutActivity_lib_glide_desc,
                icon = Icon.Drawable(R.drawable.ic_license)
            ),
            AboutItem(
                type = ItemType.LIBRARY,
                titleRes = R.string.AboutActivity_lib_documentfile,
                subtitleRes = R.string.AboutActivity_lib_documentfile_desc,
                icon = Icon.Drawable(R.drawable.ic_license)
            )
        )

        val adapter = AboutAdapter(items)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    /*
     * Queries latest GitHub release tag and release notes asynchronously on IO dispatcher.
     */
    private fun checkForUpdates(adapter: AboutAdapter, position: Int, currentVersion: String) {
        if (isCheckingUpdate) return
        isCheckingUpdate = true
        adapter.updateCheckState(position, isChecking = true, status = getString(R.string.AboutActivity_update_checking))

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://api.github.com/repos/muhammadhaseebiqbal-dev/Screen-Recorder/releases/latest")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "ScreenRecorderApp/4.0 (Android)")
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = true

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseText)
                    val tagName = json.optString("tag_name", "")
                    val releaseNotes = json.optString("body", "")
                    
                    var downloadUrl = json.optString("html_url", "https://github.com/muhammadhaseebiqbal-dev/Screen-Recorder/releases")
                    val assets = json.optJSONArray("assets")
                    if (assets != null && assets.length() > 0) {
                        val firstAsset = assets.optJSONObject(0)
                        val apkUrl = firstAsset?.optString("browser_download_url")
                        if (!apkUrl.isNullOrEmpty()) {
                            downloadUrl = apkUrl
                        }
                    }

                    val hasUpdate = isNewerVersion(currentVersion, tagName)

                    withContext(Dispatchers.Main) {
                        isCheckingUpdate = false
                        if (hasUpdate) {
                            val status = getString(R.string.AboutActivity_update_available, tagName)
                            adapter.updateCheckState(position, isChecking = false, status = status)
                            showUpdateDialog(tagName, releaseNotes, downloadUrl)
                        } else {
                            val status = getString(R.string.AboutActivity_update_latest)
                            adapter.updateCheckState(position, isChecking = false, status = status)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        isCheckingUpdate = false
                        adapter.updateCheckState(position, isChecking = false, status = getString(R.string.AboutActivity_update_error))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isCheckingUpdate = false
                    adapter.updateCheckState(position, isChecking = false, status = getString(R.string.AboutActivity_update_error))
                }
            }
        }
    }

    /*
     * Parses semantic version numbers and returns true if remote version is strictly newer.
     */
    private fun isNewerVersion(current: String, latest: String): Boolean {
        val cleanCurrent = current.replace(Regex("[^0-9.]"), "")
        val cleanLatest = latest.replace(Regex("[^0-9.]"), "")
        if (cleanCurrent.isEmpty() || cleanLatest.isEmpty()) return false

        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }
        val length = maxOf(currentParts.size, latestParts.size)

        for (i in 0 until length) {
            val curr = currentParts.getOrElse(i) { 0 }
            val lat = latestParts.getOrElse(i) { 0 }
            if (lat > curr) return true
            if (lat < curr) return false
        }
        return false
    }

    /*
     * Displays an official Material 3 dialog showing update details with direct download and cancel actions.
     */
    private fun showUpdateDialog(releaseTag: String, releaseNotes: String, downloadUrl: String) {
        val notes = if (releaseNotes.isNotBlank()) {
            releaseNotes.trim()
        } else {
            getString(R.string.AboutActivity_dialog_update_no_notes)
        }
        val message = getString(
            R.string.AboutActivity_dialog_update_msg,
            releaseTag,
            notes
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.AboutActivity_dialog_update_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.AboutActivity_dialog_update_btn_download)) { _, _ ->
                openUrl(downloadUrl)
            }
            .setNegativeButton(getString(R.string.AboutActivity_dialog_update_btn_cancel), null)
            .show()
    }

    /*
     * Safely triggers an Intent to open a target web URL in a browser.
     */
    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
        }
    }

    enum class ItemType {
        APP_INFO,
        CATEGORY_HEADER,
        CONTRIBUTOR,
        LIBRARY
    }

    data class AboutItem(
        val type: ItemType,
        val titleRes: Int = 0,
        val subtitleRes: Int = 0,
        val descriptionRes: Int = 0,
        val rawTitle: String? = null,
        val rawSubtitle: String? = null,
        val rawDescription: String? = null,
        val tags: List<String> = emptyList(),
        val primaryUrl: String? = null,
        val githubUrl: String? = null,
        val telegramUrl: String? = null,
        val emailUrl: String? = null,
        val coffeeUrl: String? = null,
        val icon: Icon? = null,
        var isChecking: Boolean = false,
        var statusText: String? = null
    )

    sealed class Icon {
        data class Drawable(val resId: Int) : Icon()
        data class Mipmap(val resId: Int) : Icon()
        data class Url(val value: String) : Icon()
    }

    inner class AboutViewHolder(val itemBinding: ItemAboutBinding) : RecyclerView.ViewHolder(itemBinding.root)

    inner class AboutAdapter(
        private val items: List<AboutItem>
    ) : RecyclerView.Adapter<AboutViewHolder>() {

        /*
         * Inflates the layout item binding and returns the ViewHolder.
         */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AboutViewHolder {
            val view = ItemAboutBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return AboutViewHolder(view)
        }

        /*
         * Configures Material 3 Expressive list shapes and binds data to view controls.
         */
        override fun onBindViewHolder(holder: AboutViewHolder, position: Int) {
            val item = items[position]
            val b = holder.itemBinding
            val root = b.root as ListItemLayout

            if (item.type != ItemType.CATEGORY_HEADER) {
                var sectionStart = position
                while (sectionStart > 0 && items[sectionStart - 1].type != ItemType.CATEGORY_HEADER) {
                    sectionStart--
                }
                
                var sectionEnd = position
                while (sectionEnd < items.size - 1 && items[sectionEnd + 1].type != ItemType.CATEGORY_HEADER) {
                    sectionEnd++
                }
                
                val sectionItemCount = sectionEnd - sectionStart + 1
                val positionInSection = position - sectionStart
                
                root.updateAppearance(positionInSection, sectionItemCount)
            }

            b.categoryContainer.visibility = View.GONE
            b.categorySubtitle.visibility = View.GONE
            b.cardView.visibility = View.GONE
            b.avatar.visibility = View.GONE
            b.libIcon.visibility = View.GONE
            b.description.visibility = View.GONE
            b.statusText.visibility = View.GONE
            b.appActionButtonsContainer.visibility = View.GONE
            b.progressBar.visibility = View.GONE
            b.tagsContainer.visibility = View.GONE
            b.tagCard1.visibility = View.GONE
            b.tagCard2.visibility = View.GONE
            b.tagCard3.visibility = View.GONE
            b.socialButtonsContainer.visibility = View.GONE
            b.btnCoffee.visibility = View.GONE
            b.btnGithub.visibility = View.GONE
            b.btnTelegram.visibility = View.GONE
            b.btnEmail.visibility = View.GONE
            
            b.cardView.isClickable = true
            b.cardView.isFocusable = true
            b.cardView.setOnClickListener(null)

            when (item.type) {
                ItemType.CATEGORY_HEADER -> {
                    b.categoryContainer.visibility = View.VISIBLE
                    if (item.titleRes != 0) {
                        b.categoryTitle.text = getString(item.titleRes)
                    }
                    if (item.subtitleRes != 0) {
                        b.categorySubtitle.visibility = View.VISIBLE
                        b.categorySubtitle.text = getString(item.subtitleRes)
                    }
                }

                ItemType.APP_INFO -> {
                    b.cardView.visibility = View.VISIBLE
                    b.avatar.visibility = View.VISIBLE
                    b.appActionButtonsContainer.visibility = View.VISIBLE
                    
                    val updateClickListener = View.OnClickListener {
                        val version = try {
                            packageManager.getPackageInfo(packageName, 0).versionName ?: "4.0"
                        } catch (e: Exception) {
                            "4.0"
                        }
                        checkForUpdates(this@AboutAdapter, position, version)
                    }
                    b.cardView.setOnClickListener(updateClickListener)
                    b.btnUpdate.setOnClickListener(updateClickListener)

                    if (item.githubUrl != null) {
                        b.btnAppGithub.visibility = View.VISIBLE
                        b.btnAppGithub.setOnClickListener { openUrl(item.githubUrl) }
                    } else {
                        b.btnAppGithub.visibility = View.GONE
                    }

                    b.title.text = if (item.titleRes != 0) getString(item.titleRes) else item.rawTitle ?: ""
                    b.subtitle.text = if (item.subtitleRes != 0) getString(item.subtitleRes) else item.rawSubtitle ?: ""

                    if (item.descriptionRes != 0) {
                        b.description.visibility = View.VISIBLE
                        b.description.text = getString(item.descriptionRes)
                    }

                    bindTags(b, item.tags)
                    loadIcon(b, item.icon, isLibrary = false)

                    if (item.isChecking) {
                        b.progressBar.visibility = View.VISIBLE
                        b.btnUpdate.visibility = View.GONE
                    } else {
                        b.progressBar.visibility = View.GONE
                        b.btnUpdate.visibility = View.VISIBLE
                    }

                    if (item.statusText != null) {
                        b.statusText.visibility = View.VISIBLE
                        b.statusText.text = item.statusText
                    }
                }

                ItemType.CONTRIBUTOR -> {
                    b.cardView.visibility = View.VISIBLE
                    b.avatar.visibility = View.VISIBLE
                    
                    b.cardView.setOnClickListener {
                        item.primaryUrl?.let { openUrl(it) }
                    }

                    b.title.text = if (item.titleRes != 0) getString(item.titleRes) else item.rawTitle ?: ""
                    b.subtitle.text = if (item.subtitleRes != 0) getString(item.subtitleRes) else item.rawSubtitle ?: ""

                    if (item.descriptionRes != 0) {
                        b.description.visibility = View.VISIBLE
                        b.description.text = getString(item.descriptionRes)
                    } else if (!item.rawDescription.isNullOrEmpty()) {
                        b.description.visibility = View.VISIBLE
                        b.description.text = item.rawDescription
                    }

                    bindTags(b, item.tags)
                    loadIcon(b, item.icon, isLibrary = false)

                    if (item.coffeeUrl != null || item.githubUrl != null || item.telegramUrl != null || item.emailUrl != null) {
                        b.socialButtonsContainer.visibility = View.VISIBLE

                        if (item.coffeeUrl != null) {
                            b.btnCoffee.visibility = View.VISIBLE
                            b.btnCoffee.setOnClickListener { openUrl(item.coffeeUrl) }
                        }

                        if (item.githubUrl != null) {
                            b.btnGithub.visibility = View.VISIBLE
                            b.btnGithub.setOnClickListener { openUrl(item.githubUrl) }
                        }

                        if (item.telegramUrl != null) {
                            b.btnTelegram.visibility = View.VISIBLE
                            b.btnTelegram.setOnClickListener { openUrl(item.telegramUrl) }
                        }

                        if (item.emailUrl != null) {
                            b.btnEmail.visibility = View.VISIBLE
                            b.btnEmail.setOnClickListener { openUrl(item.emailUrl) }
                        }
                    }
                }

                ItemType.LIBRARY -> {
                    b.cardView.visibility = View.VISIBLE
                    b.libIcon.visibility = View.VISIBLE
                    
                    b.cardView.isClickable = false
                    b.cardView.isFocusable = false
                    b.cardView.foreground = null
                    b.cardView.setOnClickListener(null)

                    b.title.text = if (item.titleRes != 0) getString(item.titleRes) else item.rawTitle ?: ""
                    b.subtitle.text = if (item.subtitleRes != 0) getString(item.subtitleRes) else item.rawSubtitle ?: ""

                    loadIcon(b, item.icon, isLibrary = true)
                }
            }
        }

        /*
         * Populates chip tags inside rounded CardViews dynamically based on input list.
         */
        private fun bindTags(b: ItemAboutBinding, tags: List<String>) {
            if (tags.isEmpty()) {
                b.tagsContainer.visibility = View.GONE
                return
            }

            b.tagsContainer.visibility = View.VISIBLE

            if (tags.size > 0) {
                b.tagCard1.visibility = View.VISIBLE
                b.tag1.text = tags[0]
            }
            if (tags.size > 1) {
                b.tagCard2.visibility = View.VISIBLE
                b.tag2.text = tags[1]
            }
            if (tags.size > 2) {
                b.tagCard3.visibility = View.VISIBLE
                b.tag3.text = tags[2]
            }
        }

        /*
         * Loads avatar, app launcher icon, or vector drawables safely into target image views.
         */
        private fun loadIcon(binding: ItemAboutBinding, icon: Icon?, isLibrary: Boolean) {
            val context = binding.root.context
            val targetView = if (isLibrary) binding.libIcon else binding.avatar

            when (icon) {
                is Icon.Mipmap -> {
                    targetView.setImageResource(icon.resId)
                    targetView.imageTintList = null
                }
                is Icon.Drawable -> {
                    targetView.setImageResource(icon.resId)
                    if (!isLibrary) {
                        targetView.imageTintList = null
                    }
                }
                is Icon.Url -> {
                    Glide.with(context)
                        .load(icon.value)
                        .transform(CircleCrop())
                        .into(targetView)
                }
                null -> {
                    targetView.setImageDrawable(null)
                }
            }
        }

        /*
         * Updates progress indicator and status message for an item at a specific position.
         */
        fun updateCheckState(position: Int, isChecking: Boolean, status: String?) {
            if (position in items.indices) {
                items[position].isChecking = isChecking
                items[position].statusText = status
                notifyItemChanged(position)
            }
        }

        /*
         * Returns the count of items in the dataset.
         */
        override fun getItemCount() = items.size
    }
}
