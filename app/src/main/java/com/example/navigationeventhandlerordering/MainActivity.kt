package com.example.navigationeventhandlerordering

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            if (true) { // Change to false to demo a parent NavigationEventHandler
                BackHandler {
                    // ❌ This takes precedence over the nested NavigationEventHandler.
                    toast("Parent BackHandler called")
                }
            } else {
                NavigationEventHandler {
                    // ✅ This does not take precedence over the nested NavigationEventHandler,
                    // unless the nested handler is disabled.
                    it.collect { /* ignore in-progress back gestures for the purposes of this demo */ }
                    toast("Parent NavigationEventHandler called")
                }
            }

            NestedNavigationEventHandler()
        }
    }

    @Composable
    fun NestedNavigationEventHandler() {
        NavigationEventHandler(enabled = { true }) {
            it.collect { /* ignore in-progress back gestures for the purposes of this demo */ }
            toast("Child NavigationEventHandler called")
        }
    }

    fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
