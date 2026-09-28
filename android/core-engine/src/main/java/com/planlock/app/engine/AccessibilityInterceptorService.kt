package com.planlock.app.engine

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.planlock.app.record.RecordEventFactory
import com.planlock.app.record.RecordStore
import com.planlock.app.security.AccessibilityHealthEvaluator
import com.planlock.app.security.AccessibilityHealthStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 无障碍拦截服务：识别前台应用，拦截黑名单应用（回到桌面）。
 */
class AccessibilityInterceptorService : AccessibilityService() {
    private val violationGate = ViolationEventGate()
    private var feedbackView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var animator: ValueAnimator? = null
    private val recordScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var recordStore: RecordStore
    private lateinit var healthStore: AccessibilityHealthStore
    private val recordFactory = RecordEventFactory()
    private val heartbeat = object : Runnable {
        override fun run() {
            healthStore.markRunning()
            handler.postDelayed(this, AccessibilityHealthEvaluator.HEARTBEAT_INTERVAL_MILLIS)
        }
    }
    private val removeFeedback = Runnable {
        feedbackView?.let { view -> runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) } }
        feedbackView = null
    }
    private val returnHome = Runnable {
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.postDelayed(removeFeedback, 120L)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        recordStore = RecordStore(this)
        healthStore = AccessibilityHealthStore(this)
        handler.removeCallbacks(heartbeat)
        heartbeat.run()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }
        val pkg = event.packageName?.toString() ?: return
        val decision = ExecutionEngine.inspect(pkg) ?: return
        if (decision.shouldBlock && shouldBlockAfterRuntimeExemptionCheck(pkg, decision)) {
            val planId = ExecutionEngine.activePlanId()
            // 一次应用启动会连续发出多个窗口事件；三秒内同计划、同包只记一次。
            if (planId != null && violationGate.shouldRecord(planId, pkg, SystemClock.elapsedRealtime())) {
                executionHaptic(this)
                ExecutionFeedback.emit(planId, FeedbackKind.BLOCKED)
                val appName = runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                }.getOrNull()
                recordScope.launch {
                    recordStore.enqueueViolation(recordFactory.violation(planId, pkg, appName, System.currentTimeMillis()))
                }
                showBlockFeedback(pkg, appName)
            } else if (feedbackView == null) {
                // 去重窗口内再次越界仍须拦截，只省略重复反馈与记录。
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
    }

    private fun shouldBlockAfterRuntimeExemptionCheck(
        packageName: String,
        decision: EnginePackageDecision,
    ): Boolean {
        if (!decision.strictMode) return true
        val snapshot = ExemptionDiagnostics.latest()
        if (snapshot == null) {
            Log.w(
                EXEMPTION_DIAGNOSTIC_TAG,
                "package=$packageName plan=${decision.planId} kind=NO_ASSEMBLY_SNAPSHOT " +
                    "engineGeneration=${decision.snapshotGeneration} strict=${decision.strictMode}",
            )
            return true
        }
        val runtimeFacts = packageFacts(packageManager, packageName)
        val gap = ExemptionGapAnalyzer.analyze(snapshot, decision, packageName, runtimeFacts)
        val resolution = RuntimeExemptionGuard.resolve(decision, gap)
        Log.w(
            EXEMPTION_DIAGNOSTIC_TAG,
            "package=$packageName plan=${decision.planId} kind=${gap.kind} " +
                "snapshotGeneration=${snapshot.generation} engineGeneration=${decision.snapshotGeneration} " +
                "snapshotKnown=${decision.classifiedInSnapshot} snapshotExempt=${decision.exemptInSnapshot} " +
                "runtimeFacts=$runtimeFacts runtimeClassification=${gap.runtimeClassification} " +
                "shouldBlockBefore=${decision.shouldBlock} recovered=${resolution.recovered} " +
                "shouldBlockAfter=${resolution.shouldBlock}",
        )
        return resolution.shouldBlock
    }

    override fun onInterrupt() {
        markStopped()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        markStopped()
        return super.onUnbind(intent)
    }

    private fun markStopped() {
        handler.removeCallbacks(heartbeat)
        if (::healthStore.isInitialized) healthStore.markStopped()
    }

    private fun showBlockFeedback(packageName: String, appLabel: String?) {
        animator?.cancel()
        handler.removeCallbacks(removeFeedback)
        handler.removeCallbacks(returnHome)
        removeFeedback.run()
        val presentation = BlockFeedbackPresentation.create(
            appLabel,
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(32), dp(28), dp(32))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(Color.rgb(70, 72, 74))
            }
            addView(ImageView(context).apply {
                contentDescription = "${presentation.appName} 应用图标"
                setImageDrawable(runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull())
            }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(20) })
            addView(TextView(context).apply {
                text = presentation.appName
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
            addView(TextView(context).apply {
                text = presentation.title
                setTextColor(Color.WHITE)
                textSize = 28f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
            addView(TextView(context).apply {
                text = presentation.message
                setTextColor(Color.rgb(220, 220, 220))
                textSize = 16f
                gravity = Gravity.CENTER
            })
        }
        val view = FrameLayout(this).apply {
            contentDescription = "受限应用遮罩"
            setBackgroundColor(Color.rgb(45, 47, 49))
            addView(content, FrameLayout.LayoutParams(dp(310), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        if (runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params) }.isFailure) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        feedbackView = view
        if (presentation.animate) {
            animator = ValueAnimator.ofFloat(.4f, 1f).apply {
                duration = 240
                addUpdateListener { view.alpha = it.animatedValue as Float }
                start()
            }
        } else {
            view.alpha = 1f
        }
        handler.postDelayed(returnHome, presentation.displayMillis)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        markStopped()
        animator?.cancel()
        handler.removeCallbacks(returnHome)
        handler.removeCallbacks(removeFeedback)
        removeFeedback.run()
        recordScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXEMPTION_DIAGNOSTIC_TAG = "PlanLockExemption"
    }
}
