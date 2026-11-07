package com.pureimage.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import com.pureimage.app.ui.PureImageScreen
import com.pureimage.app.ui.PureImageViewModel

class MainActivity : ComponentActivity() {
    private val model: PureImageViewModel by viewModels {
        PureImageViewModel.Factory((application as PureImageApplication).repository)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { PureImageScreen(model) } }
    }
}
