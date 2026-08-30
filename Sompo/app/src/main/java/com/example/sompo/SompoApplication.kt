package com.example.sompo

import android.app.Application
import android.util.Log
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.amplifyframework.core.Amplify

class SompoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            Amplify.addPlugin(AWSCognitoAuthPlugin())
            Amplify.configure(applicationContext)
            Log.i("SompoApp", "Amplify configurado com sucesso")
        } catch (e: Exception) {
            Log.e("SompoApp", "Falha ao configurar o Amplify", e)
        }
    }
}
