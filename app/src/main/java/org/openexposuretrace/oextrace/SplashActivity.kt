package org.openexposuretrace.oextrace

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import org.openexposuretrace.oextrace.storage.OnboardingManager
import org.openexposuretrace.oextrace.storage.OnboardingStage

class SplashActivity : AppCompatActivity() {
    private val SPLASH_TIME: Long = 2000
    private lateinit var mHandler: Handler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        mHandler = Handler()
    }

    override fun onPause() {
        super.onPause()
        mHandler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        mHandler.postDelayed({
            nextScreen()
            finish()
        }, SPLASH_TIME)
    }

    private fun nextScreen() {
        if (!OnboardingManager.isComplete()) {
            val intent = Intent(this, OnboardingActivity::class.java)

            intent.putExtra(OnboardingActivity.Extra.STAGE_EXTRA, OnboardingStage.WELCOME)

            startActivity(intent)

            return
        }
    }
}
