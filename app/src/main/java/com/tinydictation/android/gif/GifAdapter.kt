package com.tinydictation.android.gif

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.tinydictation.android.R

/**
 * RecyclerView adapter for displaying GIF thumbnails in a grid.
 */
class GifAdapter(
    private val onGifClick: (GifItem) -> Unit
) : ListAdapter<GifItem, GifAdapter.GifViewHolder>(GifDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GifViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gif, parent, false)
        return GifViewHolder(view)
    }

    override fun onBindViewHolder(holder: GifViewHolder, position: Int) {
        holder.bind(getItem(position), onGifClick)
    }

    class GifViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.ivGif)

        fun bind(gif: GifItem, onGifClick: (GifItem) -> Unit) {
            Glide.with(itemView.context)
                .asGif()
                .load(gif.previewUrl)
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .centerCrop()
                .into(imageView)

            itemView.setOnClickListener {
                onGifClick(gif)
            }
        }
    }

    class GifDiffCallback : DiffUtil.ItemCallback<GifItem>() {
        override fun areItemsTheSame(oldItem: GifItem, newItem: GifItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: GifItem, newItem: GifItem): Boolean {
            return oldItem == newItem
        }
    }
}
