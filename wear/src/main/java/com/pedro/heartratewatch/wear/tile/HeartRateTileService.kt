package com.pedro.heartratewatch.wear.tile

import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.pedro.heartratewatch.wear.HeartRateRepository

/**
 * A quick-glance Tile showing the current run's heart rate and distance, reachable with a
 * swipe instead of opening the full app. Reads from the same in-memory [HeartRateRepository]
 * that [com.pedro.heartratewatch.wear.ExerciseSessionService] writes to -- if no session is
 * running, it just shows dashes.
 *
 * This uses the plain LayoutElementBuilders API (stable for a long time) rather than the newer
 * Material3 tile DSL, since the latter's exact artifact coordinates were still moving around at
 * the time this was scaffolded -- worth revisiting once you're comfortable with the basics.
 */
class HeartRateTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        val state = HeartRateRepository.state.value
        val bpmText = state.currentBpm?.let { "$it bpm" } ?: "-- bpm"
        val distanceText = "%.0f m".format(state.distanceMeters)

        val layout = LayoutElementBuilders.Column.Builder()
            .addContent(textElement(bpmText, sizeSp = 28f))
            .addContent(textElement(distanceText, sizeSp = 16f))
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(
            ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
        )

    private fun textElement(value: String, sizeSp: Float) =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(sizeSp))
                    .setColor(argb(0xFFFFFFFF.toInt()))
                    .build()
            )
            .build()

    private companion object {
        const val RESOURCES_VERSION = "1"
    }
}
