package io.github.libreroute.ui

import androidx.fragment.app.Fragment
import io.github.libreroute.event.AppEvent

abstract class BaseFragment : Fragment() {
    abstract fun onNewEvent(ev: AppEvent)
}
