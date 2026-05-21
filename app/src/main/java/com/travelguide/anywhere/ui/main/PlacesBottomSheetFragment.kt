package com.travelguide.anywhere.ui.main

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.travelguide.anywhere.BuildConfig
import com.travelguide.anywhere.R
import com.travelguide.anywhere.data.local.MentionedPlacesStore
import com.travelguide.anywhere.databinding.BottomSheetPlacesBinding
import com.travelguide.anywhere.repository.PoiImageRepository
import com.travelguide.anywhere.service.TourGuideService
import com.travelguide.anywhere.service.TourState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class PlacesBottomSheetFragment : BottomSheetDialogFragment() {

    @Inject lateinit var mentionedPlacesStore: MentionedPlacesStore
    @Inject lateinit var prefs: SharedPreferences

    private var _binding: BottomSheetPlacesBinding? = null
    private val binding get() = _binding!!

    private enum class Filter { ALL, SAVED, SKIPS }
    private var currentFilter = Filter.ALL
    private val expandedOsmIds = mutableSetOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetPlacesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Expand to full height immediately.
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        sheet?.let { BottomSheetBehavior.from(it).state = BottomSheetBehavior.STATE_EXPANDED }

        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, _ ->
            currentFilter = when (binding.chipGroupFilter.checkedChipId) {
                R.id.chip_saved -> Filter.SAVED
                R.id.chip_skips -> Filter.SKIPS
                else -> Filter.ALL
            }
            populateList()
        }

        populateList()

        // Refresh when the service commits a new place.
        viewLifecycleOwner.lifecycleScope.launch {
            TourGuideService.mentionedPlaces.collect { populateList() }
        }
    }

    private fun populateList() {
        val b = _binding ?: return
        val all = mentionedPlacesStore.allSorted()
        val filtered = when (currentFilter) {
            Filter.SAVED -> all.filter { it.wantToVisit }
            Filter.SKIPS -> all.filter { it.thumbsDown || it.autoSkipped }
            Filter.ALL -> all
        }

        b.tvSheetCount.text = "(${all.size})"
        b.tvSheetEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE

        val container = b.llSheetPlaces
        container.removeAllViews()
        if (filtered.isEmpty()) return

        val sdf = SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault())
        val ctx = requireContext()
        val colorPrimary = ctx.getColor(R.color.text_primary)
        val colorSecondary = ctx.getColor(R.color.text_secondary)
        val colorAccent = ctx.getColor(R.color.accent)
        val colorRed = ctx.getColor(R.color.stop_color)

        filtered.forEachIndexed { index, entry ->
            val row = LayoutInflater.from(ctx).inflate(R.layout.item_mentioned_place, container, false)
            val tvName = row.findViewById<TextView>(R.id.tv_place_name)
            val tvSummary = row.findViewById<TextView>(R.id.tv_place_summary)
            val tvTime = row.findViewById<TextView>(R.id.tv_place_time)
            val ivSaved = row.findViewById<ImageView>(R.id.iv_want_to_visit_indicator)
            val layoutActions = row.findViewById<LinearLayout>(R.id.layout_place_actions)
            val btnNavigate = row.findViewById<ImageButton>(R.id.btn_navigate)
            val btnWikipedia = row.findViewById<ImageButton>(R.id.btn_wikipedia)
            val btnReplay = row.findViewById<ImageButton>(R.id.btn_replay)
            val btnWantToVisit = row.findViewById<ImageButton>(R.id.btn_want_to_visit)
            val btnThumbsDown = row.findViewById<ImageButton>(R.id.btn_thumbs_down)
            val tvDetails = row.findViewById<TextView>(R.id.tv_place_details)

            // Name
            tvName.text = if (entry.autoSkipped) "${entry.name}  (auto skipped)" else entry.name
            tvName.setTextColor(if (entry.autoSkipped) colorSecondary else colorPrimary)

            // Summary
            if (entry.summary.isNotBlank()) {
                tvSummary.text = entry.summary
                tvSummary.visibility = View.VISIBLE
            }

            // Deep dives heard about this place
            val llDeepDives = row.findViewById<LinearLayout>(R.id.ll_place_deep_dives)
            llDeepDives.removeAllViews()
            if (entry.deepDives.isNotEmpty()) {
                llDeepDives.visibility = View.VISIBLE
                entry.deepDives.forEachIndexed { i, title ->
                    val tv = TextView(ctx).apply {
                        text = "${i + 1}. $title"
                        textSize = 12f
                        setTextColor(colorSecondary)
                        setPadding(0, 2, 0, 2)
                    }
                    llDeepDives.addView(tv)
                }
            } else {
                llDeepDives.visibility = View.GONE
            }

            tvTime.text = sdf.format(Date(entry.mentionedAt))

            // Saved indicator in name row
            ivSaved.imageTintList = android.content.res.ColorStateList.valueOf(colorAccent)
            ivSaved.visibility = if (entry.wantToVisit) View.VISIBLE else View.GONE

            // Show action row
            layoutActions.visibility = View.VISIBLE

            // Navigate
            btnNavigate.imageTintList = android.content.res.ColorStateList.valueOf(colorSecondary)
            btnNavigate.setOnClickListener { openNavigation(entry.lat, entry.lon, entry.name) }

            // Wikipedia — prefer stored URL, fall back to deriving from tags
            val wikiUrl = entry.wikipediaUrl ?: entry.tags["wikipedia"]?.let { tag ->
                val colon = tag.indexOf(':')
                if (colon >= 0) {
                    val lang = tag.substring(0, colon)
                    val title = tag.substring(colon + 1)
                    if (title.lowercase().trim() !in PoiImageRepository.GENERIC_WIKIPEDIA_TITLES)
                        "https://$lang.wikipedia.org/wiki/${title.replace(' ', '_')}"
                    else null
                } else null
            }
            btnWikipedia.imageTintList = android.content.res.ColorStateList.valueOf(colorSecondary)
            if (wikiUrl != null) {
                btnWikipedia.visibility = View.VISIBLE
                btnWikipedia.setOnClickListener { openUrl(wikiUrl) }
            }

            // Want to Visit
            applyWantToVisitState(btnWantToVisit, ivSaved, entry.wantToVisit, colorAccent, colorSecondary)
            if (!entry.autoSkipped) {
                btnWantToVisit.setOnClickListener {
                    mentionedPlacesStore.markWantToVisit(entry.osmId)
                    TourGuideService.mentionedPlaces.value = mentionedPlacesStore.recentFive()
                    populateList()
                }
            } else {
                btnWantToVisit.isEnabled = false
                btnWantToVisit.alpha = 0.3f
            }

            // Thumbs down
            applyThumbsDownState(btnThumbsDown, entry.thumbsDown, colorRed, colorSecondary)
            if (!entry.autoSkipped) {
                btnThumbsDown.setOnClickListener {
                    mentionedPlacesStore.markThumbsDown(entry.osmId)
                    TourGuideService.mentionedPlaces.value = mentionedPlacesStore.recentFive()
                    populateList()
                }
            } else {
                btnThumbsDown.isEnabled = false
                btnThumbsDown.alpha = 0.3f
            }

            // Replay
            btnReplay.imageTintList = android.content.res.ColorStateList.valueOf(colorSecondary)
            if (!entry.autoSkipped) {
                btnReplay.setOnClickListener { showReplayDialog(entry) }
            } else {
                btnReplay.isEnabled = false
                btnReplay.alpha = 0.3f
            }

            // Details (expand on row tap) — useful for troubleshooting POI selection
            val expanded = expandedOsmIds.contains(entry.osmId)
            if (expanded) {
                tvDetails.text = buildDetailsText(entry)
                tvDetails.visibility = View.VISIBLE
            } else {
                tvDetails.visibility = View.GONE
            }
            row.setOnClickListener {
                if (expandedOsmIds.contains(entry.osmId)) expandedOsmIds.remove(entry.osmId)
                else expandedOsmIds.add(entry.osmId)
                populateList()
            }

            container.addView(row)
            if (index < filtered.lastIndex) {
                val divider = View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(ctx.getColor(R.color.stroke))
                }
                container.addView(divider)
            }
        }
    }

    private fun buildDetailsText(entry: MentionedPlacesStore.Entry): String = buildString {
        appendLine("osm_id: ${entry.osmId}")
        appendLine("lat: ${entry.lat}")
        appendLine("lon: ${entry.lon}")
        if (entry.tags.isEmpty()) {
            appendLine()
            append("(no OSM tags stored — pre-2.6.2 entry)")
        } else {
            appendLine()
            entry.tags.entries.sortedBy { it.key }.forEach { (k, v) ->
                appendLine("$k = $v")
            }
        }
    }.trimEnd()

    private fun applyWantToVisitState(btn: ImageButton, indicator: ImageView, active: Boolean, activeColor: Int, inactiveColor: Int) {
        btn.setImageResource(if (active) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        btn.imageTintList = android.content.res.ColorStateList.valueOf(if (active) activeColor else inactiveColor)
        indicator.visibility = if (active) View.VISIBLE else View.GONE
    }

    private fun applyThumbsDownState(btn: ImageButton, active: Boolean, activeColor: Int, inactiveColor: Int) {
        btn.imageTintList = android.content.res.ColorStateList.valueOf(if (active) activeColor else inactiveColor)
        btn.alpha = if (active) 1f else 0.5f
    }

    private fun showReplayDialog(entry: MentionedPlacesStore.Entry) {
        val ctx = requireContext()
        if (TourGuideService.tourState.value != TourState.IDLE) {
            Toast.makeText(ctx, "Stop the tour first to replay a place", Toast.LENGTH_SHORT).show()
            return
        }
        val apiKey = prefs.getString(MainFragment.PREF_API_KEY, "")
            ?.takeIf { it.isNotBlank() } ?: BuildConfig.ANTHROPIC_API_KEY
        if (apiKey.isBlank() || apiKey == "YOUR_API_KEY_HERE") {
            Toast.makeText(ctx, "API key not configured", Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(entry.name)
            .setMessage(buildString {
                if (entry.summary.isNotBlank()) { appendLine(entry.summary); appendLine() }
                append("Hear about this place again?")
            })
            .setPositiveButton("Replay") { _, _ ->
                ctx.startForegroundService(Intent(ctx, TourGuideService::class.java).apply {
                    action = TourGuideService.ACTION_REPLAY_POI
                    putExtra(TourGuideService.EXTRA_POI_OSM_ID, entry.osmId)
                    putExtra(TourGuideService.EXTRA_POI_NAME, entry.name)
                    putExtra(TourGuideService.EXTRA_POI_LAT, entry.lat)
                    putExtra(TourGuideService.EXTRA_POI_LON, entry.lon)
                    putExtra(TourGuideService.EXTRA_API_KEY, apiKey)
                })
                Toast.makeText(ctx, "Generating narration…", Toast.LENGTH_SHORT).show()
                dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openNavigation(lat: Double, lon: Double, name: String) {
        val uri = Uri.parse("geo:$lat,$lon?q=${Uri.encode(name)}")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "No maps app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "No browser found", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
