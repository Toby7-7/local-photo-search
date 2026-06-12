package com.photosearch.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.photosearch.app.ui.configurePhotoThumbnailImageLoader
import com.photosearch.app.ui.PhotoSearchApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configurePhotoThumbnailImageLoader(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            PhotoSearchApp(appContext = applicationContext)
        }
    }
}
