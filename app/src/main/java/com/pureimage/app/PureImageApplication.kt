package com.pureimage.app

import android.app.Application
import com.pureimage.app.data.ImageRepository

class PureImageApplication : Application() {
    val repository by lazy { ImageRepository(this) }
}
