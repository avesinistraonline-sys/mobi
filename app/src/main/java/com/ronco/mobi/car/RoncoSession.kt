package com.ronco.mobi.car

import android.content.Intent
import androidx.car.app.AppManager
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.ronco.mobi.Profiles

class RoncoSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        Profiles.ensureLoaded(carContext)          // o carro pode abrir sem o app do celular ter sido aberto
        val renderer = SpeedRenderer()
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { renderer.release() }
        })
        return SpeedScreen(carContext, renderer)
    }
}
