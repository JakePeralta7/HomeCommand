package net.elad.homecommand.ui.logs

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import net.elad.homecommand.R
import net.elad.homecommand.data.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter(
    private val onClick: (AppLog.Entry) -> Unit = {},
) : ListAdapter<AppLog.Entry, LogAdapter.LogViewHolder>(LogDiffCallback()) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.view_log_entry, parent, false)
        return LogViewHolder(view, onClick)
    }

    override fun onBindViewHolder(
        holder: LogViewHolder,
        position: Int,
    ) {
        holder.bind(getItem(position))
    }

    class LogViewHolder(
        itemView: View,
        private val onClick: (AppLog.Entry) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        private val dot: View = itemView.findViewById(R.id.dot_level)
        private val timestamp: TextView = itemView.findViewById(R.id.text_timestamp)
        private val tag: TextView = itemView.findViewById(R.id.text_tag)
        private val message: TextView = itemView.findViewById(R.id.text_message)

        fun bind(entry: AppLog.Entry) {
            val color =
                when (entry.level) {
                    AppLog.Level.DEBUG -> R.color.log_debug
                    AppLog.Level.INFO -> R.color.log_info
                    AppLog.Level.WARN -> R.color.log_warn
                    AppLog.Level.ERROR -> R.color.log_error
                }
            dot.background.setTint(ContextCompat.getColor(itemView.context, color))
            timestamp.text = TIME_FORMAT.format(Date(entry.timestamp))
            tag.text = entry.tag
            message.text = entry.message
            itemView.setOnClickListener { onClick(entry) }
        }
    }

    class LogDiffCallback : DiffUtil.ItemCallback<AppLog.Entry>() {
        override fun areItemsTheSame(
            oldItem: AppLog.Entry,
            newItem: AppLog.Entry,
        ) = oldItem.timestamp == newItem.timestamp && oldItem.tag == newItem.tag

        override fun areContentsTheSame(
            oldItem: AppLog.Entry,
            newItem: AppLog.Entry,
        ) = oldItem == newItem
    }

    companion object {
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    }
}
