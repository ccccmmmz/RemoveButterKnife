package com.github.joehaivo.removebutterknife.utils

import com.intellij.psi.PsiClass

/**
 * 统计修改记录
 * 1 所有受影响的类
 * 2 安全插入点 直接在butterKnife.bind位置
 * 3 其他位置
 */
object TrackKit {

    //直接插入在butterKnife.bind之后的 安全插入
    private val mAfterBindCase by lazy { HashMap<String, String>() }

    //k className v methodName
    private val mAllModify by lazy { HashMap<String, String>() }

    private val mStepViewCase by lazy { HashMap<String, String>() }

    private val mOnlyDeleteImport by lazy { hashSetOf<String>() }

    private val mNoDeleteCase by lazy { hashSetOf<String>() }

    @Synchronized
    fun modifyAfterButterKnifeBind(psiClass: PsiClass){
        mAfterBindCase[psiClass.name.orEmpty()] = "butterKnife.bind"
        mAllModify[psiClass.name.orEmpty()] = "butterKnife.bind"
    }

    @Synchronized
    fun trackOtherModify(psiClass: PsiClass, methodName : String){
        mStepViewCase[psiClass.name.orEmpty()] = methodName
        mAllModify[psiClass.name.orEmpty()] = methodName
    }

    @Synchronized
    fun onDeleteImport(psiClass: PsiClass){
        mAllModify[psiClass.name.orEmpty()] = "onlyDeleteImport"
        mOnlyDeleteImport.add(psiClass.name.orEmpty())
    }

    @Synchronized
    fun onNoDelete(psiClass: PsiClass){
        mAllModify[psiClass.name.orEmpty()] = "hasButterKnifeButNoDelete"
        mNoDeleteCase.add(psiClass.name.orEmpty())
    }



    fun printModifyLog() : String{
        val resultPrint =
            "total size ${mAllModify.size}, after bind cast ${mAfterBindCase.size} files= ${mAfterBindCase.keys}, \n\n\n" +
                    "other case ${mStepViewCase.size} files = $mStepViewCase \n\n\n, " +
                    "onlyDelete cast ${mOnlyDeleteImport.size}, files = $mOnlyDeleteImport \n\n\n" +
                    ", noDelete case ${mNoDeleteCase.size} files = $mNoDeleteCase"
        reset()
        return resultPrint
    }

    fun getModifyLog() : Array<String>{
        val arrayOf = arrayOf("ButterKnife.bind( insert ${mAfterBindCase.keys}", "other case insert $mStepViewCase",
        "mOnlyDeleteImport case $mOnlyDeleteImport", "no handle delete $mNoDeleteCase")
        reset()
        return arrayOf
    }

    private fun reset(){
        mAllModify.clear()
        mAfterBindCase.clear()
        mStepViewCase.clear()
        mOnlyDeleteImport.clear()
        mNoDeleteCase.clear()
    }



}