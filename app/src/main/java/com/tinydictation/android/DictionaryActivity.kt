package com.tinydictation.android

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tinydictation.android.dictionary.WordReplacement
import com.tinydictation.android.dictionary.WordReplacementManager

class DictionaryActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerWords: RecyclerView
    private lateinit var emptyState: View
    private lateinit var btnAddWord: MaterialButton
    private lateinit var replacementManager: WordReplacementManager
    private lateinit var adapter: WordReplacementAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dictionary)

        replacementManager = WordReplacementManager.getInstance(this)

        initViews()
        setupToolbar()
        setupRecyclerView()
        setupAddButton()
        loadWords()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        recyclerWords = findViewById(R.id.recyclerWords)
        emptyState = findViewById(R.id.emptyState)
        btnAddWord = findViewById(R.id.btnAddWord)
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        adapter = WordReplacementAdapter(
            onEditClick = { word -> showEditDialog(word) },
            onDeleteClick = { word -> showDeleteConfirmation(word) }
        )

        recyclerWords.layoutManager = LinearLayoutManager(this)
        recyclerWords.adapter = adapter
    }

    private fun setupAddButton() {
        btnAddWord.setOnClickListener {
            showAddDialog()
        }
    }

    private fun loadWords() {
        val words = replacementManager.getAll()
        adapter.submitList(words)
        updateEmptyState(words.isEmpty())
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        recyclerWords.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun showAddDialog() {
        showWordDialog(
            title = "Add Word Replacement",
            originalText = "",
            replacementText = "",
            onSave = { original, replacement ->
                replacementManager.add(original, replacement)
                loadWords()
            }
        )
    }

    private fun showEditDialog(word: WordReplacement) {
        showWordDialog(
            title = "Edit Word Replacement",
            originalText = word.original,
            replacementText = word.replacement,
            onSave = { original, replacement ->
                replacementManager.update(word.id, original, replacement)
                loadWords()
            }
        )
    }

    private fun showWordDialog(
        title: String,
        originalText: String,
        replacementText: String,
        onSave: (String, String) -> Unit
    ) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_word_replacement, null)

        val tilOriginal = dialogView.findViewById<TextInputLayout>(R.id.tilOriginal)
        val etOriginal = dialogView.findViewById<TextInputEditText>(R.id.etOriginal)
        val tilReplacement = dialogView.findViewById<TextInputLayout>(R.id.tilReplacement)
        val etReplacement = dialogView.findViewById<TextInputEditText>(R.id.etReplacement)

        etOriginal.setText(originalText)
        etReplacement.setText(replacementText)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val original = etOriginal.text?.toString()?.trim() ?: ""
                val replacement = etReplacement.text?.toString()?.trim() ?: ""

                // Validate inputs
                var hasError = false

                if (original.isEmpty()) {
                    tilOriginal.error = "Original word is required"
                    hasError = true
                } else {
                    tilOriginal.error = null
                }

                if (replacement.isEmpty()) {
                    tilReplacement.error = "Replacement word is required"
                    hasError = true
                } else {
                    tilReplacement.error = null
                }

                if (!hasError) {
                    onSave(original, replacement)
                    dialog.dismiss()
                }
            }
        }

        dialog.show()

        // Focus on first field
        etOriginal.requestFocus()
    }

    private fun showDeleteConfirmation(word: WordReplacement) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Word Replacement")
            .setMessage("Are you sure you want to delete the replacement \"${word.original}\" -> \"${word.replacement}\"?")
            .setPositiveButton("Delete") { _, _ ->
                replacementManager.delete(word.id)
                loadWords()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * RecyclerView adapter for word replacement items.
     */
    private inner class WordReplacementAdapter(
        private val onEditClick: (WordReplacement) -> Unit,
        private val onDeleteClick: (WordReplacement) -> Unit
    ) : RecyclerView.Adapter<WordReplacementAdapter.ViewHolder>() {

        private var words: List<WordReplacement> = emptyList()

        fun submitList(newWords: List<WordReplacement>) {
            words = newWords
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_word_replacement, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(words[position])
        }

        override fun getItemCount(): Int = words.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvOriginal: TextView = itemView.findViewById(R.id.tvOriginal)
            private val tvReplacement: TextView = itemView.findViewById(R.id.tvReplacement)
            private val btnEdit: ImageButton = itemView.findViewById(R.id.btnEdit)
            private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

            fun bind(word: WordReplacement) {
                tvOriginal.text = word.original
                tvReplacement.text = word.replacement

                btnEdit.setOnClickListener { onEditClick(word) }
                btnDelete.setOnClickListener { onDeleteClick(word) }
            }
        }
    }
}
