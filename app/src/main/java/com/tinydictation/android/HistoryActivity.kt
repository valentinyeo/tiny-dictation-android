package com.tinydictation.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.tinydictation.android.history.TranscriptionEntry
import com.tinydictation.android.history.TranscriptionHistoryManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: View
    private lateinit var fabCopy: FloatingActionButton
    private lateinit var fabBack: FloatingActionButton
    private lateinit var historyManager: TranscriptionHistoryManager
    private lateinit var adapter: HistoryAdapter

    // Selection mode state
    private var isSelectionMode = false
    private val selectedItems = mutableSetOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        historyManager = TranscriptionHistoryManager.getInstance(this)

        initViews()
        setupRecyclerView()
        setupFabs()
        setupBackPressHandler()
        loadHistory()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerView)
        emptyState = findViewById(R.id.emptyState)
        fabCopy = findViewById(R.id.fabCopySelected)
        fabBack = findViewById(R.id.fabBack)
    }

    private fun setupRecyclerView() {
        adapter = HistoryAdapter(
            onItemClick = { entry -> handleItemClick(entry) },
            onItemLongClick = { entry -> handleItemLongClick(entry) },
            isItemSelected = { id -> selectedItems.contains(id) },
            isInSelectionMode = { isSelectionMode }
        )

        // Use GridLayoutManager with 2 columns for tile layout
        val gridLayoutManager = GridLayoutManager(this, 2)
        recyclerView.layoutManager = gridLayoutManager
        recyclerView.adapter = adapter
    }

    private fun setupFabs() {
        // Back FAB - always visible, in thumb zone
        fabBack.setOnClickListener {
            if (isSelectionMode) {
                exitSelectionMode()
            } else {
                finish()
            }
        }

        // Copy FAB - shown when items selected
        fabCopy.setOnClickListener {
            copySelectedToClipboard()
        }
        updateFabVisibility()
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSelectionMode) {
                    exitSelectionMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun handleItemClick(entry: TranscriptionEntry) {
        if (isSelectionMode) {
            toggleSelection(entry.id)
        } else {
            // Single tap copies and closes - thumb-zone friendly
            copyToClipboardAndClose(entry)
        }
    }

    private fun handleItemLongClick(entry: TranscriptionEntry) {
        if (!isSelectionMode) {
            enterSelectionMode()
            toggleSelection(entry.id)
        }
    }

    private fun enterSelectionMode() {
        isSelectionMode = true
        adapter.notifyDataSetChanged()
        updateFabVisibility()
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedItems.clear()
        updateFabVisibility()
        adapter.notifyDataSetChanged()
    }

    private fun toggleSelection(id: Long) {
        if (selectedItems.contains(id)) {
            selectedItems.remove(id)
        } else {
            selectedItems.add(id)
        }

        // Exit selection mode if no items selected
        if (selectedItems.isEmpty()) {
            exitSelectionMode()
        } else {
            updateFabVisibility()
            adapter.notifyDataSetChanged()
        }
    }

    private fun updateFabVisibility() {
        if (isSelectionMode && selectedItems.isNotEmpty()) {
            fabCopy.show()
        } else {
            fabCopy.hide()
        }
    }

    private fun copySelectedToClipboard() {
        val entries = historyManager.getAll()
        val selectedTexts = entries
            .filter { selectedItems.contains(it.id) }
            .sortedBy { it.timestamp } // Oldest first for multi-select copy
            .map { it.text }

        if (selectedTexts.isNotEmpty()) {
            val combinedText = selectedTexts.joinToString("\n\n")
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Transcriptions", combinedText)
            clipboard.setPrimaryClip(clip)

            val count = selectedTexts.size
            Toast.makeText(
                this,
                resources.getQuantityString(R.plurals.history_copied_count, count, count),
                Toast.LENGTH_SHORT
            ).show()

            // Close after copying selected items
            finish()
        }
    }

    private fun loadHistory() {
        val entries = historyManager.getAll()
        // Reverse order: oldest at top, newest at bottom (for thumb-zone access)
        val reversedEntries = entries.sortedBy { it.timestamp }
        adapter.submitList(reversedEntries)
        updateEmptyState(entries.isEmpty())

        // Scroll to bottom to show newest items (in thumb zone)
        if (reversedEntries.isNotEmpty()) {
            recyclerView.post {
                recyclerView.scrollToPosition(reversedEntries.size - 1)
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun copyToClipboardAndClose(entry: TranscriptionEntry) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Transcription", entry.text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.history_copied, Toast.LENGTH_SHORT).show()
        // Close immediately after copying for quick workflow
        finish()
    }

    /**
     * RecyclerView adapter for history tiles.
     */
    private inner class HistoryAdapter(
        private val onItemClick: (TranscriptionEntry) -> Unit,
        private val onItemLongClick: (TranscriptionEntry) -> Unit,
        private val isItemSelected: (Long) -> Boolean,
        private val isInSelectionMode: () -> Boolean
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

                // Update card checked state for selection mode
                val selected = isItemSelected(entry.id)
                card.isChecked = selected

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
                    // Today - just show time
                    now.get(Calendar.DATE) == then.get(Calendar.DATE) &&
                    now.get(Calendar.MONTH) == then.get(Calendar.MONTH) &&
                    now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> {
                        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                        timeFormat.format(date)
                    }
                    // Yesterday
                    now.get(Calendar.DATE) - then.get(Calendar.DATE) == 1 &&
                    now.get(Calendar.MONTH) == then.get(Calendar.MONTH) &&
                    now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> {
                        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                        "Yest ${timeFormat.format(date)}"
                    }
                    // Same year - short date
                    now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> {
                        val format = SimpleDateFormat("M/d h:mm a", Locale.getDefault())
                        format.format(date)
                    }
                    // Different year
                    else -> {
                        val format = SimpleDateFormat("M/d/yy", Locale.getDefault())
                        format.format(date)
                    }
                }
            }
        }
    }
}
