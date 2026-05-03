package com.haseeb.recorder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.google.android.material.listitem.ListItemViewHolder
import com.haseeb.recorder.databinding.LayoutAboutItemBinding
import com.haseeb.recorder.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    /**
     * Initializes About screen and binds recycler data.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecycler()
    }

    /**
     * Prepares list items.
     */
    private fun setupRecycler() {
        val items = listOf(
            AboutItem(
                "Source Code",
                "View project repository",
                "https://github.com/muhammadhaseebiqbal-dev/Screen-Recorder",
                Icon.Drawable(R.drawable.ic_github)
            ),
            AboutItem(
                "Haseeb Iqbal",
                "Maintainer",
                "https://github.com/muhammadhaseebiqbal-dev",
                Icon.Url("https://github.com/muhammadhaseebiqbal-dev.png")
            ),
            AboutItem(
                "Ameer Muawiya",
                "Collaborator",
                "https://github.com/ameermuawiya",
                Icon.Url("https://github.com/ameermuawiya.png")
            )
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = AboutAdapter(items)
    }

    /**
     * Opens external browser for provided link.
     */
    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    data class AboutItem(
        val title: String,
        val subtitle: String,
        val url: String,
        val icon: Icon?
    )

    /**
     * Represents all supported icon sources in a type-safe way.
     */
    sealed class Icon {
        data class Drawable(val resId: Int) : Icon()
        data class Url(val value: String) : Icon()
        data class Asset(val path: String) : Icon()
    }

    inner class AboutAdapter(
        private val items: List<AboutItem>
    ) : RecyclerView.Adapter<ListItemViewHolder>() {

        /**
         * Inflates item layout.
         */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ListItemViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_about_item, parent, false)
            return ListItemViewHolder(view)
        }

        /**
         * Binds data and resolves icon source dynamically.
         */
        override fun onBindViewHolder(holder: ListItemViewHolder, position: Int) {
            val item = items[position]
            holder.bind(position, itemCount)

            val binding = LayoutAboutItemBinding.bind(holder.itemView)

            binding.title.text = item.title
            binding.subtitle.text = item.subtitle

            loadIcon(binding, item.icon)

            binding.root.setOnClickListener {
                openUrl(item.url)
            }
        }

        /**
         * Resolves icon from Drawable, URL or Assets automatically.
         */
        private fun loadIcon(binding: LayoutAboutItemBinding, icon: Icon?) {
            val context = binding.root.context

            when (icon) {
                is Icon.Drawable -> {
                    binding.avatar.setImageResource(icon.resId)
                }

                is Icon.Url -> {
                    Glide.with(context)
                        .load(icon.value)
                        .transform(CircleCrop())
                        .into(binding.avatar)
                }

                is Icon.Asset -> {
                    Glide.with(context)
                        .load("file:///android_asset/${icon.path}")
                        .transform(CircleCrop())
                        .into(binding.avatar)
                }

                null -> {
                    binding.avatar.setImageDrawable(null)
                }
            }
        }

        /**
         * Returns total item count.
         */
        override fun getItemCount() = items.size
    }
}