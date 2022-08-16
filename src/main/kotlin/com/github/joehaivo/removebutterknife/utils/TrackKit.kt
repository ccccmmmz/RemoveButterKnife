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

    //step方法或者构造方法插入
    private val mStepViewCase by lazy { HashMap<String, String>() }

    //只有bind操作没有@bindView或者onClick
    private val mOnlyDeleteImport by lazy { hashSetOf<String>() }
    //没匹配到
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