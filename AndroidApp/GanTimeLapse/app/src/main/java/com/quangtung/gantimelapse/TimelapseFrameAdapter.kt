package com.quangtung.gantimelapse

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.quangtung.gantimelapse.R
import com.quangtung.gantimelapse.databinding.ItemFrameBinding

class TimelapseFrameAdapter(
    val frames: MutableList<Bitmap>
) : RecyclerView.Adapter<TimelapseFrameAdapter.FrameViewHolder>() {

    inner class FrameViewHolder(val binding: ItemFrameBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FrameViewHolder {
        val binding = ItemFrameBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FrameViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FrameViewHolder, position: Int) {
        holder.binding.ivFrame.setImageBitmap(frames[position])
    }

    override fun getItemCount(): Int = frames.size

    fun moveItem(from: Int, to: Int) {
        val moved = frames.removeAt(from)
        frames.add(to, moved)
        notifyItemMoved(from, to)
    }
}
