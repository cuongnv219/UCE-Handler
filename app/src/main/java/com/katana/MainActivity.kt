package com.katana

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {

    val mFirebaseAnalytics = FirebaseAnalytics.getInstance(this);

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnCrash.setOnClickListener {
            val bundle = Bundle();
            bundle.putString(FirebaseAnalytics.Param.ITEM_ID, "id");
            bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, "name");
            bundle.putString(FirebaseAnalytics.Param.CONTENT_TYPE, "image");
            mFirebaseAnalytics.logEvent(FirebaseAnalytics.Event.SELECT_ITEM, bundle)
        }
    }
}
