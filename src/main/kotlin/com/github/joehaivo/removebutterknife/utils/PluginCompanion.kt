package com.github.joehaivo.removebutterknife.utils

import com.intellij.psi.PsiImportStatement

/**
 * @author gen.li
 * @date 2022/8/4
 */
object PluginCompanion {

    var mImportViewStatement: PsiImportStatement? = null
        get() = field

    fun onMatchImport(psiImportStatement: PsiImportStatement) {
        if (mImportViewStatement == null) {
            mImportViewStatement = psiImportStatement
        }

    }
}
