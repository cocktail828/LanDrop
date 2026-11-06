package io.github.landrop.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import io.github.landrop.R
import io.github.landrop.model.RemoteDevice
import io.github.landrop.settings.DeviceType

class DeviceAdapter(
    private val onDeviceClick: (RemoteDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    private val devices = mutableListOf<RemoteDevice>()

    fun submitDevices(newDevices: List<RemoteDevice>) {
        val oldDevices = devices.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldDevices.size
            override fun getNewListSize(): Int = newDevices.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldDevices[oldItemPosition].fingerprint == newDevices[newItemPosition].fingerprint
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldDevices[oldItemPosition] == newDevices[newItemPosition]
        })
        devices.clear()
        devices.addAll(newDevices)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val itemView = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        ThemeColors.apply(itemView)
        return DeviceViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position], onDeviceClick)
    }

    override fun getItemCount(): Int = devices.size

    class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val alias: TextView = itemView.findViewById(R.id.device_alias)
        private val details: TextView = itemView.findViewById(R.id.device_details)
        private val deviceTypeIcon: ImageView = itemView.findViewById(R.id.device_type_icon)
        private val infoButton: ImageView = itemView.findViewById(R.id.device_info_button)

        fun bind(
            device: RemoteDevice,
            onDeviceClick: (RemoteDevice) -> Unit
        ) {
            alias.text = device.alias
            val deviceType = DeviceType.fromValue(device.deviceType)
            deviceTypeIcon.setImageResource(deviceTypeIconResource(deviceType))
            deviceTypeIcon.imageTintList = ColorStateList.valueOf(ThemeColors.primaryColor(itemView.context))
            deviceTypeIcon.contentDescription = itemView.context.getString(deviceTypeLabelResource(deviceType))
            infoButton.imageTintList = ColorStateList.valueOf(ThemeColors.primaryColor(itemView.context))
            val deviceName = device.deviceModel ?: device.deviceType ?: "Unknown device"
            details.text = deviceName
            itemView.setOnClickListener { onDeviceClick(device) }
            infoButton.isFocusable = false
            infoButton.isClickable = false
            infoButton.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        private fun deviceTypeIconResource(type: DeviceType): Int = when (type) {
            DeviceType.MOBILE -> R.drawable.ic_device
            DeviceType.DESKTOP -> R.drawable.ic_desktop
            DeviceType.WEB -> R.drawable.ic_language
            DeviceType.HEADLESS -> R.drawable.ic_code
            DeviceType.SERVER -> R.drawable.ic_server
        }

        private fun deviceTypeLabelResource(type: DeviceType): Int = when (type) {
            DeviceType.MOBILE -> R.string.device_type_mobile
            DeviceType.DESKTOP -> R.string.device_type_desktop
            DeviceType.WEB -> R.string.device_type_web
            DeviceType.HEADLESS -> R.string.device_type_headless
            DeviceType.SERVER -> R.string.device_type_server
        }
    }
}
