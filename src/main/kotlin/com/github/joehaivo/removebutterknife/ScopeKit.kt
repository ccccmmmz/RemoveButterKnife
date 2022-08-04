package com.github.joehaivo.removebutterknife

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * @author gen.li
 * @date 2022/8/4
 */
object ScopeKit {
    private val defaultExecutor by lazy { MainScope() }

    fun executorDefault(block: () -> Unit){
        defaultExecutor.launch(Dispatchers.Default) {
            block()
        }
    }
}