package com.travelguide.anywhere.ui.main

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val m = Matrix()
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: ScaleGestureDetector): Boolean {
                val newScale = (scale * det.scaleFactor).coerceIn(0.5f, 8f)
                val ratio = newScale / scale
                tx = det.focusX - ratio * (det.focusX - tx)
                ty = det.focusY - ratio * (det.focusY - ty)
                scale = newScale
                commit()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                fitCenter()
                return true
            }
        })

    init { scaleType = ScaleType.MATRIX }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitCenter()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { fitCenter() }
    }

    private fun fitCenter() {
        val d = drawable ?: return
        val vW = width.toFloat().takeIf { it > 0 } ?: return
        val vH = height.toFloat().takeIf { it > 0 } ?: return
        val dW = d.intrinsicWidth.toFloat().takeIf { it > 0 } ?: return
        val dH = d.intrinsicHeight.toFloat().takeIf { it > 0 } ?: return
        scale = minOf(vW / dW, vH / dH)
        tx = (vW - dW * scale) / 2f
        ty = (vH - dH * scale) / 2f
        commit()
    }

    private fun commit() {
        m.setScale(scale, scale)
        m.postTranslate(tx, ty)
        imageMatrix = m
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    tx += event.x - lastX
                    ty += event.y - lastY
                    commit()
                }
                lastX = event.x
                lastY = event.y
            }
        }
        return true
    }
}
