package com.haseeb.recorder

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.listitem.ListItemViewHolder
import com.haseeb.recorder.databinding.LayoutDialogEditTextBinding
import com.haseeb.recorder.databinding.LayoutVideoItemBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter for managing and displaying video files.
 * Implements Material 3 Expressive styling and efficient list updates.
 */
class VideoAdapter : ListAdapter<VideoFile, ListItemViewHolder>(VideoDiffCallback()) {

    /**
     * Inflates the item layout and creates the ViewHolder.
     * Uses manual inflation to avoid compatibility issues with the create method.
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ListItemViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.layout_video_item, parent, false)
        return ListItemViewHolder(view)
    }

    /**
     * Binds video data to the UI components.
     * Also updates the item shape based on its position in the list.
     */
    override fun onBindViewHolder(holder: ListItemViewHolder, position: Int) {
        val video = getItem(position)
        
        /* Updates the corner shapes for M3 Expressive Lists.
           Handles first, middle, last, and single item states.
        */
        holder.bind(position, itemCount)

        val binding = LayoutVideoItemBinding.bind(holder.itemView)
        bindMetadata(binding, video)
        
        binding.root.setOnClickListener { openVideo(it.context, video.uri) }
        binding.btnMenu.setOnClickListener { showPopupMenu(it, video) }
    }

    /**
     * Refreshes shapes for all items when the list content changes.
     * Ensures corners are correctly adjusted when an item is added or removed.
     */
    override fun onCurrentListChanged(previousList: MutableList<VideoFile>, currentList: MutableList<VideoFile>) {
        super.onCurrentListChanged(previousList, currentList)
        // Refresh all items to update their Material shapes (top, middle, bottom)
        notifyItemRangeChanged(0, itemCount, "shape_refresh")
    }

    /**
     * Updates metadata text and thumbnail for the video item.
     * Uses Glide for optimized image loading.
     */
    private fun bindMetadata(binding: LayoutVideoItemBinding, video: VideoFile) {
        binding.titleText.text = video.name.removeSuffix(".mp4")

        val sizeMb = video.size / (1024f * 1024f)
        val duration = String.format(Locale.US, "%02d:%02d", (video.duration / 1000) / 60, (video.duration / 1000) % 60)
        val date = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(video.dateAdded * 1000L)).uppercase()
        
        binding.durationText.text = duration
        binding.dateSizeText.text = "$date  •  ${String.format(Locale.US, "%.1f MB", sizeMb)}"

        Glide.with(binding.root)
            .load(video.uri)
            .centerCrop()
            .error(R.drawable.ic_screen_record)
            .into(binding.thumbnail)
    }

    /**
     * Opens the video file in an external player.
     * Checks for available players and handles read permissions.
     */
    private fun openVideo(context: android.content.Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.VideoAdapter_toast_no_player), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Shows a popup menu for video management.
     * Provides options to rename, share, or delete the file.
     */
    private fun showPopupMenu(view: android.view.View, video: VideoFile) {
        PopupMenu(view.context, view).apply {
            menuInflater.inflate(R.menu.video_options_menu, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_rename -> { showRenameDialog(video, view.context); true }
                    R.id.action_share -> { shareVideo(video, view.context); true }
                    R.id.action_delete -> { showDeleteDialog(video, view.context); true }
                    else -> false
                }
            }
            show()
        }
    }

    /**
     * Opens a dialog to rename the selected video.
     * Updates MediaStore display name if the input is valid.
     */
    private fun showRenameDialog(video: VideoFile, context: android.content.Context) {
        val binding = LayoutDialogEditTextBinding.inflate(LayoutInflater.from(context))
        binding.editText.setText(video.name.removeSuffix(".mp4"))

        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.VideoAdapter_dialog_rename_title))
            .setView(binding.root)
            .setPositiveButton(context.getString(R.string.VideoAdapter_btn_rename)) { _, _ ->
                val newName = binding.editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val values = ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, "$newName.mp4") }
                    try {
                        context.contentResolver.update(video.uri, values, null, null)
                    } catch (e: Exception) {
                        Toast.makeText(context, context.getString(R.string.VideoAdapter_toast_rename_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(context.getString(R.string.VideoAdapter_btn_cancel), null)
            .show()
    }

    /**
     * Starts the system sharing chooser for the video.
     * Includes grant flags for the content URI.
     */
    private fun shareVideo(video: VideoFile, context: android.content.Context) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, video.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.VideoAdapter_chooser_share)))
    }

    /**
     * Displays a confirmation dialog before deleting the video.
     * Handles MediaStore deletion for various Android versions.
     */
    private fun showDeleteDialog(video: VideoFile, context: android.content.Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.VideoAdapter_dialog_delete_title))
            .setMessage(context.getString(R.string.VideoAdapter_dialog_delete_msg))
            .setPositiveButton(context.getString(R.string.VideoAdapter_btn_delete)) { _, _ ->
                try {
                    val rowsDeleted = context.contentResolver.delete(video.uri, null, null)
                    if (rowsDeleted <= 0 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, listOf(video.uri))
                        (context as AppCompatActivity).startIntentSenderForResult(pendingIntent.intentSender, 1001, null, 0, 0, 0)
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.VideoAdapter_toast_delete_failed), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(context.getString(R.string.VideoAdapter_btn_cancel), null)
            .show()
    }

    /**
     * Callback for calculating list differences.
     * Allows ListAdapter to update items efficiently with animations.
     */
    private class VideoDiffCallback : DiffUtil.ItemCallback<VideoFile>() {
        override fun areItemsTheSame(oldItem: VideoFile, newItem: VideoFile) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: VideoFile, newItem: VideoFile) = oldItem == newItem
    }
}
