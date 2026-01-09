package com.tinydictation.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.tinydictation.android.history.TranscriptionEntry
import com.tinydictation.android.history.TranscriptionHistoryManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: View
    private lateinit var historyManager: TranscriptionHistoryManager
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        historyManager = TranscriptionHistoryManager.getInstance(this)

        initViews()
        setupToolbar()
        setupRecyclerView()
        loadHistory()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        recyclerView = findViewById(R.id.recyclerView)
        emptyState = findViewById(R.id.emptyState)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_clear_all -> {
                    showClearAllDialog()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = HistoryAdapter(
            onItemClick = { entry -> copyToClipboard(entry) },
            onItemLongClick = { entry -> showItemOptions(entry) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun loadHistory() {
        val entries = historyManager.getAll()
        adapter.submitList(entries)
        updateEmptyState(entries.isEmpty())
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun copyToClipboard(entry: TranscriptionEntry) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Transcription", entry.text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.history_copied, Toast.LENGTH_SHORT).show()
    }

    private fun showItemOptions(entry: TranscriptionEntry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.history_item_options_title)
            .setItems(R.array.history_item_options) { _, which ->
                when (which) {
                    0 -> copyToClipboard(entry)
                    1 -> deleteEntry(entry)
                }
            }
            .show()
    }

    private fun deleteEntry(entry: TranscriptionEntry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.history_delete_title)
            .setMessage(R.string.history_delete_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                historyManager.delete(entry.id)
                loadHistory()
                Toast.makeText(this, R.string.history_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showClearAllDialog() {
        if (historyManager.getCount() == 0) {
            Toast.makeText(this, R.string.history_already_empty, Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.history_clear_all_title)
            .setMessage(R.string.history_clear_all_message)
            .setPositiveButton(R.string.clear_all) { _, _ ->
                historyManager.clear()
                loadHistory()
                Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * RecyclerView adapter for history items.
     */
    private inner class HistoryAdapter(
        private val onItemClick: (TranscriptionEntry) -> Unit,
        private val onItemLongClick: (TranscriptionEntry) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        private var entries: List<TranscriptionEntry> = emptyList()

        fun submitList(newEntries: List<TranscriptionEntry>) {
            entries = newEntries
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(entries[position])
        }

        override fun getItemCount(): Int = entries.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val card: MaterialCardView = itemView.findViewById(R.id.cardHistory)
            private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
            private val tvLanguage: TextView = itemView.findViewById(R.id.tvLanguage)
            private val tvText: TextView = itemView.findViewById(R.id.tvText)

            fun bind(entry: TranscriptionEntry) {
                tvTimestamp.text = formatTimestamp(entry.timestamp)
                tvLanguage.text = entry.language.uppercase()
                tvText.text = entry.text

                card.setOnClickListener { onItemClick(entry) }
                card.setOnLongClickListener {
                    onItemLongClick(entry)
                    true
                }
            }

            private fun formatTimestamp(timestamp: Long): String {
                val date = Date(timestamp)
                val now = Calendar.getInstance()
                val then = Calendar.getInstance().apply { time = date }

                return when {
                    // Today
                    now.get(Calendar.DATE) == then.get(Calendar.DATE) &&
                    now.get(Calendar.MONTH) == then.get(Calendar.MONTH) &&
                    now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> {
                        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                        "Today, ${timeFormat.format(date)}"
                    }
                    // Yesterday
                    now.get(Calendar.DATE) - then.get(Calendar.DATE) == 1 &&
                    now.get(Calendar.MONTH) == then.get(Calendar.MONTH) &&
                    now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> {
                        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                        "Yesterday, ${timeFormat.format(date)}"
                    }
                    // Same year
                    now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> {
                        val format = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                        format.format(date)
                    }
                    // Different year
                    else -> {
                        val format = SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault())
                        format.format(date)
                    }
                }
            }
        }
    }
}
