package com.tinydictation.android.gif

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tinydictation.android.R
import com.tinydictation.android.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages the tabbed Emoji/GIF panel.
 * Handles tab switching, GIF search, and content display.
 */
class EmojiGifPanelManager(
    private val context: Context,
    private val panelView: View,
    private val onEmojiSelected: (String) -> Unit,
    private val onGifSelected: (GifItem) -> Unit,
    private val getInputConnection: () -> InputConnection?
) {
    companion object {
        private const val TAG = "TinyDictation"
    }

    enum class Tab { EMOJI, GIF }

    private var currentTab = Tab.EMOJI
    private var giphyClient: GiphyApiClient? = null
    private var searchJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    // Views
    private val tabEmoji: View = panelView.findViewById(R.id.tabEmoji)
    private val tabGif: View = panelView.findViewById(R.id.tabGif)
    private val indicatorEmoji: View = panelView.findViewById(R.id.indicatorEmoji)
    private val indicatorGif: View = panelView.findViewById(R.id.indicatorGif)
    private val emojiPicker: EmojiPickerView = panelView.findViewById(R.id.emojiPicker)
    private val gifContent: View = panelView.findViewById(R.id.gifContent)
    private val etGifSearch: EditText = panelView.findViewById(R.id.etGifSearch)
    private val rvGifs: RecyclerView = panelView.findViewById(R.id.rvGifs)
    private val gifStateContainer: View = panelView.findViewById(R.id.gifStateContainer)
    private val progressGif: ProgressBar = panelView.findViewById(R.id.progressGif)
    private val tvGifMessage: TextView = panelView.findViewById(R.id.tvGifMessage)

    private val gifAdapter = GifAdapter { gif ->
        onGifClicked(gif)
    }

    init {
        setupTabs()
        setupEmojiPicker()
        setupGifGrid()
        setupGifSearch()
    }

    private fun setupTabs() {
        tabEmoji.setOnClickListener { switchToTab(Tab.EMOJI) }
        tabGif.setOnClickListener { switchToTab(Tab.GIF) }
    }

    private fun setupEmojiPicker() {
        emojiPicker.setOnEmojiPickedListener { emojiViewItem ->
            onEmojiSelected(emojiViewItem.emoji)
        }
    }

    private fun setupGifGrid() {
        rvGifs.layoutManager = GridLayoutManager(context, 2)
        rvGifs.adapter = gifAdapter
    }

    private fun setupGifSearch() {
        etGifSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = etGifSearch.text.toString().trim()
                if (query.isNotEmpty()) {
                    searchGifs(query)
                }
                true
            } else {
                false
            }
        }
    }

    fun switchToTab(tab: Tab) {
        currentTab = tab

        // Update tab indicators
        indicatorEmoji.setBackgroundResource(
            if (tab == Tab.EMOJI) R.color.bubble_recording else android.R.color.transparent
        )
        indicatorGif.setBackgroundResource(
            if (tab == Tab.GIF) R.color.bubble_recording else android.R.color.transparent
        )

        // Update tab text style
        panelView.findViewById<TextView>(R.id.tvTabEmoji)?.apply {
            setTypeface(null, if (tab == Tab.EMOJI) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        panelView.findViewById<TextView>(R.id.tvTabGif)?.apply {
            setTypeface(null, if (tab == Tab.GIF) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }

        // Show/hide content
        emojiPicker.isVisible = tab == Tab.EMOJI
        gifContent.isVisible = tab == Tab.GIF

        // Load trending GIFs when switching to GIF tab
        if (tab == Tab.GIF && gifAdapter.itemCount == 0) {
            initializeGiphyClient()
            loadTrendingGifs()
        }
    }

    private fun initializeGiphyClient() {
        if (giphyClient != null) return

        val prefs = PreferenceManager(context)
        val apiKey = prefs.getGiphyApiKey()

        if (apiKey.isNullOrBlank()) {
            showGifMessage("Enter Giphy API key in Settings")
            return
        }

        giphyClient = GiphyApiClient(apiKey)
    }

    private fun loadTrendingGifs() {
        val client = giphyClient ?: return

        showGifLoading()

        scope.launch {
            val result = client.getTrending()
            result.fold(
                onSuccess = { gifs ->
                    if (gifs.isEmpty()) {
                        showGifMessage("No trending GIFs")
                    } else {
                        showGifResults(gifs)
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to load trending GIFs", error)
                    showGifMessage("Failed to load GIFs")
                }
            )
        }
    }

    private fun searchGifs(query: String) {
        val client = giphyClient
        if (client == null) {
            initializeGiphyClient()
            if (giphyClient == null) return
        }

        searchJob?.cancel()
        showGifLoading()

        searchJob = scope.launch {
            val result = giphyClient!!.searchGifs(query)
            result.fold(
                onSuccess = { gifs ->
                    if (gifs.isEmpty()) {
                        showGifMessage("No GIFs found for \"$query\"")
                    } else {
                        showGifResults(gifs)
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to search GIFs", error)
                    showGifMessage("Search failed")
                }
            )
        }
    }

    private fun showGifLoading() {
        rvGifs.isVisible = false
        gifStateContainer.isVisible = true
        progressGif.isVisible = true
        tvGifMessage.isVisible = false
    }

    private fun showGifMessage(message: String) {
        rvGifs.isVisible = false
        gifStateContainer.isVisible = true
        progressGif.isVisible = false
        tvGifMessage.isVisible = true
        tvGifMessage.text = message
    }

    private fun showGifResults(gifs: List<GifItem>) {
        gifStateContainer.isVisible = false
        rvGifs.isVisible = true
        gifAdapter.submitList(gifs)
    }

    private fun onGifClicked(gif: GifItem) {
        Log.d(TAG, "GIF clicked: ${gif.id} - ${gif.title}")

        // Copy GIF URL to clipboard as a fallback
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("GIF URL", gif.originalUrl)
        clipboard.setPrimaryClip(clip)

        // Insert the URL into the text field
        getInputConnection()?.commitText(gif.originalUrl, 1)

        onGifSelected(gif)

        Toast.makeText(context, "GIF URL inserted", Toast.LENGTH_SHORT).show()
    }

    /**
     * Returns true if the GIF search field is currently focused.
     * Used to redirect voice transcription to the search field.
     */
    fun isGifSearchFocused(): Boolean {
        return gifContent.isVisible && etGifSearch.hasFocus()
    }

    /**
     * Inserts text into the GIF search field.
     * Used for voice transcription when the search field is focused.
     */
    fun insertTextInGifSearch(text: String) {
        val start = etGifSearch.selectionStart
        val end = etGifSearch.selectionEnd
        etGifSearch.text.replace(start.coerceAtLeast(0), end.coerceAtLeast(0), text)
    }
}
