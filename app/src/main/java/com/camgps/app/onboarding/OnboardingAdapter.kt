package com.camgps.app.onboarding

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import com.camgps.app.databinding.ItemOnboardingPageBinding

data class OnboardingItem(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val iconTint: Int? = null
)

class OnboardingAdapter(
    private val items: List<OnboardingItem>
) : RecyclerView.Adapter<OnboardingAdapter.OnboardingViewHolder>() {

    inner class OnboardingViewHolder(val binding: ItemOnboardingPageBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OnboardingViewHolder {
        val binding = ItemOnboardingPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return OnboardingViewHolder(binding)
    }

    override fun onBindViewHolder(holder: OnboardingViewHolder, position: Int) {
        val item = items[position]
        holder.binding.ivOnboardingIcon.setImageResource(item.iconRes)
        if (item.iconTint != null) {
            holder.binding.ivOnboardingIcon.imageTintList = ColorStateList.valueOf(item.iconTint)
        }
        holder.binding.tvOnboardingTitle.setText(item.titleRes)
        holder.binding.tvOnboardingDesc.setText(item.descRes)
    }

    override fun getItemCount(): Int = items.size
}
