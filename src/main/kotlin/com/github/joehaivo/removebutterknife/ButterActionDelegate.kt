package com.github.joehaivo.removebutterknife

import com.github.joehaivo.removebutterknife.utils.Logger
import com.github.joehaivo.removebutterknife.utils.Notifier
import com.github.joehaivo.removebutterknife.utils.PluginCompanion
import com.github.joehaivo.removebutterknife.utils.TrackKit
import com.intellij.lang.java.JavaImportOptimizer
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.impl.source.tree.java.PsiCodeBlockImpl
import com.intellij.psi.impl.source.tree.java.PsiReferenceExpressionImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.util.ArrayUtil
import org.apache.http.util.TextUtils
import org.jetbrains.kotlin.idea.util.ifTrue
import org.jetbrains.kotlin.psi.psiUtil.getChildrenOfType
import java.util.*
import java.util.function.Predicate


class ButterActionDelegate(
    private val e: AnActionEvent,
    private val psiJavaFile: PsiJavaFile,
    private val psiClass: PsiClass
) {
    private val project = e.project
    private val elementFactory = JavaPsiFacade.getInstance(project!!).elementFactory

    /**
     * 代码插入(findViewById和__bindClicks())所在的锚点方法 eg: fun onCreate() | fun onCreateView()
     */
    private var anchorMethod: PsiMethod? = null

    /**
     * 代码插入所在的锚点语句 // eg: Butterknife.bind() | super.onCreate() | 内部类的super(view)
     */
    private var anchorStatement: PsiElement? = null

    /**
     * unbinder = Butterknife.bind(this, view)表达式中的参数view
     */
    private var butterknifeView: String? = null

    /**
     * unbinder = Butterknife.bind(this, view) | Butterknife.bind(this, view) | Butterknife.bind(this)
     */
    private var butterknifeBindStatement: PsiElement? = null

    private var deBouncingClass: PsiClass? = null

    private val mMatchMethodSet by lazy { hashSetOf("stepAllViews", "onInitilizeView") }

    /**
     * view import
     */
    private val mViewImportState = "android.view.View"

    private var mImportAndroidViewElement : PsiStatement? = null

    private val mButterKnifeBindEntry = "ButterKnife.bind("

    private var statementInIfStatement = false

    fun parse(): Boolean {
        if (!checkIsNeedModify()) {
            return false
        }
        //replaceDebouncingOnClickListener()

        val (bindViewFields, bindViewAnnotations) = collectBindViewAnnotation(psiClass.fields)
        val (bindClickVos, onClickAnnotations) = collectOnClickAnnotation(psiClass)
        var needInterrupt = false
        if (bindClickVos.isNotEmpty() || bindViewFields.isNotEmpty()) {
            //有@bindView或者@OnClick的
            val pair = findAnchors(psiClass)
            if (pair.first == null) {
                //没有找到锚点信息 暂时不删除相关代码
                needInterrupt = true
                Notifier.notifyError(project!!, "RemoveButterKnife tools: 未在文件${psiClass.name}找到合适的代码插入位置，跳过 pair = $pair")
            } else {
                anchorMethod = pair.first
                anchorStatement = pair.second
                /**
                 * anchorStatement是butterKnife.bind锚点统计类名,不是的话统计类名 方法名
                 */

                if (anchorStatement?.text?.startsWith(mButterKnifeBindEntry) == true){
                    TrackKit.modifyAfterButterKnifeBind(psiClass)
                } else {
                    TrackKit.trackOtherModify(psiClass, anchorMethod?.name.orEmpty())
                }
                insertBindViewsMethod(psiClass, bindViewFields, bindViewAnnotations)
                insertBindClickMethod(psiClass, bindClickVos, onClickAnnotations)
            }
        } else {
            //没有bindView 或者 @OnClick的
            TrackKit.onDeleteImport(psiClass)
        }

        //没有找到锚点暂时不删除相关
        if (!needInterrupt) {
            deleteButterKnifeStatement(psiClass)
            deleteImportButterKnife()

        } else {
            //有butterKnife相关,但是没删除的
            TrackKit.onNoDelete(psiClass)
        }
        // 内部类
        handleInnerClass(psiClass.innerClasses)
        return true
    }

    private fun checkIsNeedModify(): Boolean {
        val importStatement = psiJavaFile.importList?.importStatements?.find {
            it.qualifiedName?.contains(mViewImportState)?.ifTrue {
                PluginCompanion.onMatchImport(it)
            }
            it.qualifiedName?.lowercase()?.contains("butterknife") == true
        }
        return importStatement != null
    }

    private fun writeImport(importContent: String){
        if (TextUtils.isEmpty(importContent)) {
            return
        }
        writeAction{

            val clazz = findClazz(project, importContent)
            if (clazz.isPresent) {
                psiJavaFile.importList?.add(elementFactory.createImportStatement(clazz.get()))
            } else {
                //找不到
                elementFactory.createImportStatementOnDemand(importContent).apply {
                    psiJavaFile.importList?.add(this)
                }
            }

        }
    }

    private fun deleteButterKnifeStatement(psiClass: PsiClass) {
        writeAction {
            if (anchorStatement?.text?.contains("ButterKnife") == true) {
                anchorStatement?.delete()
            }
            try {
                butterknifeBindStatement?.delete()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
            //基类只有ButterKnife.bind情况
            // 再找一遍是否存在"ButterKnife.bind("
            /**
             * deleteButterKnifeStatement methods= PsiMethod:onCreateView , statement = PsiIfStatement, statement content = if (0 != getFragLayoutId()) {
                View view = inflater.inflate(getFragLayoutId(), container, false);
                mRootView = new AutoSpeedFrameLayout(view.getContext()).wrap(view, new PageDrawImpl());
                ButterKnife.bind(this, mRootView);
                mContainerView.addView(mRootView);
                 }
             */

            val bindStates = mutableListOf<PsiElement>()
            val findButterKnifeBind = findButterKnifeBind(psiClass)
            findButterKnifeBind.second.takeIf { it != null }.let { bindStates.add(it!!) }

            bindStates.forEach { it.delete() }

            // unbinderField: private Unbinder bind;
            val unbinderField = psiClass.fields.find {
                it.type.canonicalText.contains("Unbinder")
            }
            if (unbinderField != null) {
                psiClass.methods.forEach {
                    it.body?.statements?.forEach { state ->
                        val theState = state.firstChild
                        // theState： unbinder.unbind();
                        if (theState is PsiMethodCallExpression) {
                            val unbinderRef = theState.firstChild.firstChild as? PsiReferenceExpressionImpl
                            if (unbinderField.type == unbinderRef?.type) {
                                state?.delete()
                            }
                        }
                        // state： if (unbinder != null) {}
                        if (state is PsiIfStatement) {
                            val child = state.condition?.firstChild
                            // 若第一个变量类型是unbinder， 则把这个if语句整个删除
                            if (child is PsiReferenceExpression) {
                                if (child.type == unbinderField.type) {
                                    state.delete()
                                }
                            }
                        }
                    }
                }
                unbinderField.delete()
            }
        }
    }

    // 寻找代码插入的锚点：例如onCreate()方法以及内部ButterKnife.bind()语句
    private fun findAnchors(psiClass: PsiClass): Pair<PsiMethod?, PsiElement?> {
        var pair = findButterKnifeBind(psiClass)

        if (pair.second == null){
            pair = findCustomProjectImpl(psiClass)
            if (pair.first != null) {
                return pair
            }

        }
        if (pair.second == null) {
            pair = findOnCreateView(psiClass)
        }
        if (pair.second == null) {
            pair = findOnViewCreated(psiClass)
        }
        if (pair.second == null) {
            pair = findOnCreate(psiClass)
        }
        if (pair.second == null) {
            pair = insertInflaterViewStatementOnCreateView()
        }
        if (pair.second == null) {
            pair = findConstructorAsAnchor(psiClass)
        }
        if (pair.second == null) {
            pair = createOnCreateViewMethodInFragment(psiClass)
        }
        return pair
    }

    /**
     * allMethods 包括了超类的方法
     * methods 只是当前类的方法
     */
    private fun findCustomProjectImpl(psiClass: PsiClass) : Pair<PsiMethod?, PsiStatement?>{
        var bindMethod = psiClass.methods.find {
            mMatchMethodSet.contains(it.name)
        }


        //当前类的方法找不到, 查找超类的
        if (bindMethod == null) {
            val superMatchMethod = psiClass.allMethods.find {
                mMatchMethodSet.contains(it.name)
            }
            superMatchMethod.takeIf { it != null }?.let {
                //创建一个超类方法的实现
                val method = elementFactory.createMethodFromText(
                    "@Override\n" +
                            "    protected void stepAllViews(View root, Bundle savedInstanceState) {\n" +
                            "        super.stepAllViews(root, savedInstanceState);\n" +
                            "    }", psiClass
                )
                bindMethod = psiClass.addAfter(method, psiClass.methods[0]) as? PsiMethod
                method

            }
        }
        if (bindMethod == null) {
            return Pair(null, null)
        }

        butterknifeView = "root"
        val size = bindMethod?.body?.statements?.size
        return Pair(bindMethod, if (size != 0) bindMethod?.body?.statements?.get(0) else {
            bindMethod?.firstChild as? PsiStatement
        })
    }

    private fun insertBindViewsMethod(
        psiClass: PsiClass,
        bindViewFields: Map<String, PsiField>,
        bindViewAnnotations: MutableList<PsiAnnotation>
    ) {
        if (bindViewFields.isEmpty()) {
            return
        }
        // 构建__bindViews()方法及方法体
        val args = if (butterknifeView.isNullOrEmpty()) "" else "View $butterknifeView"
        var bindViewsMethod =
            elementFactory.createMethodFromText("private void __bindViews(${args}) {}\n", this.psiClass)
        writeAction {
            val caller = if (butterknifeView.isNullOrEmpty()) "" else "${butterknifeView}."
            bindViewFields.forEach { (R_id_view, psiField) ->
                val findViewStr = "${psiField.name} = ${caller}findViewById($R_id_view);\n"
                val findViewState = elementFactory.createStatementFromText(findViewStr, this.psiClass)
                bindViewsMethod.lastChild.add(findViewState)
            }
            // 将__bindViews(){}插入到anchorMethod之后
            try {
                bindViewsMethod = psiClass.addAfter(bindViewsMethod, anchorMethod) as PsiMethod
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // 将__bindViews();调用插入到anchorMethod里anchorStatement之后
            //stepView impl
            val parameterList = this.anchorMethod?.parameterList?.parameters
            val para = if (butterknifeView.isNullOrEmpty()) {
                if (parameterList?.size == 0) "" else parameterList?.get(0)?.name
            } else butterknifeView

            val callBindViewsState = elementFactory.createStatementFromText("__bindViews($para);\n", this.psiClass)
            if (anchorStatement == null) {
                anchorStatement = anchorMethod?.lastChild
                if (anchorStatement is PsiCodeBlockImpl) {
                    anchorStatement = (anchorStatement as PsiCodeBlockImpl).firstBodyElement
                }
            }

            //插入替换方法 插入到第一个statement之前
            //判断anchorStatement是否是super语句
            var anchorSuper = false
            if (anchorStatement?.text?.startsWith("super.") == true || anchorStatement?.text?.startsWith("super(") == true){
                anchorSuper = true
            }
            anchorStatement = if (anchorSuper || statementInIfStatement) {
                //插入锚点是super 放之后
                anchorMethod?.addAfter(callBindViewsState, anchorStatement)
            } else {
                //不是super 放之前 有if表达式情况要放之后
                anchorMethod?.addBefore(callBindViewsState, anchorStatement)
            }


            bindViewAnnotations.forEach {
                it.delete()
            }
        }
    }

    // 删除import butterKnife.*语句
    private fun deleteImportButterKnife() {
        var packageName = ""
        psiJavaFile.importList?.importStatements?.filter {
            it.qualifiedName?.contains("butterknife") == true ||
                    it.qualifiedName?.contains(".R2") == true

        }?.forEach { importStatement ->
            importStatement.qualifiedName?.contains(".R2")?.ifTrue {
                packageName = importStatement.qualifiedName.orEmpty().replace(".R2", "")
            }
            writeAction {
                importStatement.delete()
            }
        }
        //匹配*.R;导包
        val psiImportStatement = psiJavaFile.importList?.importStatements?.find {
            val qualifiedName = it.qualifiedName.orEmpty()
            qualifiedName.isNotEmpty() && qualifiedName.substring(qualifiedName.length - 2, qualifiedName.length) == ".R"
        }


        //不存在R导包 找到报名
        if (psiImportStatement == null) {
            writeImport(packageName)
        }

    }


    // 找到`ButterKnife.bind(`语句及所在方法
    private fun findButterKnifeBind(psiClass: PsiClass): Pair<PsiMethod?, PsiStatement?> {

        var pair : Pair<PsiMethod?, PsiStatement?>
        var anchorMethod : PsiMethod? = null
        var anchorState : PsiStatement? = null
        //anchorState 所处的 if代码块 用于判断回滚
        var anchorIfState : PsiStatement? = null


        /**
         * 直接从方法中找anchor
         * 1 anchor在if代码块中
         * 2 anchor不在if代码块中
         * 3 不存在if代码块
         */
        psiClass.methods.forEach { it ->
            //方法体中没有if代码块
            //直接找bind的PsiExpressionStatement
            val expressionArray = it.body?.getChildrenOfType<PsiExpressionStatement>()
            if (expressionArray?.isNotEmpty() == true) {
                val psiExpressionStatement = expressionArray.find {
                    it.text.startsWith(mButterKnifeBindEntry)
                }
                if (psiExpressionStatement != null) {
                    anchorMethod = it
                    anchorState = psiExpressionStatement
                }

            }

            if (anchorMethod != null){
                //已经找到anchor
                return@forEach
            }
            //if body快中含有bind操作
            //if 判断语句中条件跟bind的参数正好一直则放在if条件之后,并输出
            val ifStateArray = it.body?.getChildrenOfType<PsiIfStatement>()
            if (ifStateArray.isNullOrEmpty().not()) {
                ifStateArray?.forEach { statement ->
                    val lastChild = statement.lastChild
                    if (lastChild is PsiBlockStatement) {
                        val expressionStateArray = lastChild.codeBlock.getChildrenOfType<PsiExpressionStatement>()
                        if (expressionStateArray.isNotEmpty()) {
                            val psiExpressionStatement = expressionStateArray.find {
                                it.text.startsWith(mButterKnifeBindEntry)
                            }
                            if (psiExpressionStatement != null) {
                                //if代码块中插入
                                anchorMethod = it
                                anchorState = psiExpressionStatement
                                anchorIfState = statement
                            }

                        }
                    }
                }
            }
        }
        pair = Pair(anchorMethod, anchorState)

        if (pair.second != null) {
            butterknifeBindStatement = pair.second
        }

        val theBindState = butterknifeBindStatement?.firstChild
        // 针对内部类的, firstChild: ButterKnife.bind(this，itemView)
        if (theBindState is PsiMethodCallExpression) {
            //ButterKnife.bind(View)
            if (theBindState.argumentList.expressionCount >= 1) {
                butterknifeView = theBindState.argumentList.expressions.lastOrNull()?.text
                ////ButterKnife.bind(this)
                if (butterknifeView == "this") {
                    butterknifeView = ""
                }
            }

            //查找if条件判断内容
            val binaryExpressions = anchorIfState?.getChildrenOfType<PsiBinaryExpression>()
            if (binaryExpressions.isNullOrEmpty()) {
                //不存在
            } else {
                binaryExpressions.forEach {
                    //左表达式或者右表达式跟ButterKnife.bind()的参数内容一样则锚点定位到if代码块
                    if (it.lOperand.text.contains(butterknifeView.orEmpty()) || it.rOperand?.text?.contains(butterknifeView.orEmpty()) == true) {
                        //butterknifeView 作为判断条件的话将anchor放在if代码块后边
                        pair = Pair(anchorMethod, anchorIfState)
                        statementInIfStatement = true
                    }
                }
            }
        }
        // firstChild: unbinder = ButterKnife.bind(this, view)
        if (theBindState is PsiAssignmentExpression) {
            val bindMethodCall = theBindState.lastChild
            if (bindMethodCall is PsiMethodCallExpression && bindMethodCall.argumentList.expressionCount == 2) {
                butterknifeView = bindMethodCall.argumentList.expressions.lastOrNull()?.text
            }
        }

        //butterknifeView 如果是findView出来或者其他转化出来的 转化成field
        if (butterknifeView?.contains(".") == true){
            val convertButterBindView = elementFactory.createStatementFromText(
                "View refactorView = $butterknifeView;",
                psiClass
            )
            writeAction{
                //todo 检查view导包
                /**
                 * 当前插入代码 View refactorView = $butterknifeView;
                 * 如果当前导包缺少 需要补View的导包
                 */
                pair.first?.addBefore(convertButterBindView, pair.second)
                insertViewImportIfAbsent(psiClass)
            }
            butterknifeView = "refactorView";

        }
        return pair
    }


    private fun insertViewImportIfAbsent(psiClass: PsiClass){
        val findViewImport = psiJavaFile.importList?.importStatements?.find {
            it.text.contains("android.view.View", true)
        }
        if (findViewImport == null) {
//            val lastChild = psiJavaFile.importList?.lastChild
//            val importElement =
//                elementFactory.createImportStatementOnDemand(mViewImportState)
////            导入import android.view.*
//            //log("importElement = $importElement, is psistate = ${importElement is PsiStatement == true}")
//
//            //log("插入 $mViewImportState 时 ${PluginCompanion.mImportViewStatement}")
//
//            psiJavaFile.importList?.addAfter(PluginCompanion.mImportViewStatement?: return, lastChild)
            writeImport(mViewImportState)
        } else {
            //有view导包
        }
    }

    // 找到`super.onCreateView(`语句及所在方法
    private fun findOnCreateView(psiClass: PsiClass): Pair<PsiMethod?, PsiStatement?> {
        val pair = findStatement(psiClass) {
            it.firstChild.text.trim().contains("super.onCreateView(")
        }
        if (pair.second != null) {
            butterknifeView = "view"
        }
        return pair
    }

    private fun findOnViewCreated(psiClass: PsiClass): Pair<PsiMethod?, PsiStatement?> {
        val pair: Pair<PsiMethod?, PsiStatement?> = Pair(null, null)
        val onViewCreatedMethod = psiClass.methods.find { it.text.contains("onViewCreated(") } ?: return pair
        val firstState = onViewCreatedMethod.body?.statements?.firstOrNull() ?: return pair
        butterknifeView = "view"
        return pair.copy(onViewCreatedMethod, firstState)
    }

    // 找到`super.onCreate(`语句及所在方法
    private fun findOnCreate(psiClass: PsiClass): Pair<PsiMethod?, PsiStatement?> {
        return findStatement(psiClass) {
            it.firstChild.text.trim().startsWith("super.onCreate(")
        }
    }

    // 当存在provideLayout()，并且没有找到ButterKnife.bind( | super.onCreateView( | super.onCreate(时， 插入View _view = inflater.inflate()
    private fun insertInflaterViewStatementOnCreateView(): Pair<PsiMethod?, PsiStatement?> {
        var pair: Pair<PsiMethod?, PsiStatement?> = Pair(null, null)
        val onCreateViewMethod = psiClass.methods.find {
            it.text.contains("View onCreateView(")
        } ?: return pair
        val provideLayoutMethod = psiClass.methods.find {
            it.text.contains("int provideLayout(")
        } ?: return pair
        val onCreateViewParams = onCreateViewMethod.parameterList.parameters
        if (onCreateViewParams.size == 3) {
            val inflateViewState = elementFactory.createStatementFromText(
                "View _view = ${onCreateViewParams[0].name}.inflate(provideLayout(), ${onCreateViewParams[1].name}, false);",
                psiClass
            )
            var insertedState: PsiElement? = null
            writeAction {
                val body = onCreateViewMethod.body
                if (body != null && body.statementCount > 0) {
                    insertedState = body.addBefore(inflateViewState, body.statements[0])
                } else {
                    insertedState = body?.add(inflateViewState)
                }
            }
            if (insertedState != null) {
                butterknifeView = "_view"
                pair = Pair(onCreateViewMethod, insertedState as PsiStatement)
            }
        }
        return pair
    }

    private fun findConstructorAsAnchor(psiClass: PsiClass): Pair<PsiMethod?, PsiStatement?> {
        var pair: Pair<PsiMethod?, PsiStatement?> = Pair(null, null)
        if (!ArrayUtil.isEmpty(psiClass.constructors)) {
            val targetConstructor = psiClass.constructors.find {
                val pView = it.parameterList.parameters.find { p ->
                    p.type.canonicalText.contains("View")
                }
                if (pView != null) {
                    butterknifeView = pView.name
                }
                pView != null
            }
            pair = Pair(targetConstructor, null)
            if (!ArrayUtil.isEmpty(targetConstructor?.body?.statements)) {
                pair = Pair(targetConstructor, targetConstructor?.body?.statements?.get(0)!!)
            }
        }
        return pair
    }

    private fun createOnCreateViewMethodInFragment(psiClass: PsiClass): Pair<PsiMethod?, PsiStatement?> {
        var pair: Pair<PsiMethod?, PsiStatement?> = Pair(null, null)
        val anchorMethod = psiClass.methods.find {
            it.text.contains("myInit(") // 目前myInit()方法只存在于Fragment
        } ?: return pair

        val onCreateViewMethod = elementFactory.createMethodFromText(
            "@Override\n" +
                    "    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) { }",
            psiClass
        )
        val statement1 = elementFactory.createStatementFromText(
            "View view = inflater.inflate(provideLayout(), container, false);",
            psiClass
        )
        val statement2 = elementFactory.createStatementFromText(
            "return super.onCreateView(inflater, container, savedInstanceState);",
            psiClass
        )

        writeAction {
            val method = psiClass.addBefore(onCreateViewMethod, anchorMethod) as? PsiMethod
            val anchorStatement = method?.lastChild?.add(statement1) as? PsiStatement
            method?.lastChild?.add(statement2)
            pair = pair.copy(method, anchorStatement)
            val importOptimizer = JavaImportOptimizer()
            if (importOptimizer.supports(psiJavaFile)) {
                importOptimizer.processFile(psiJavaFile).run()
            }
        }
        butterknifeView = "view"
        return pair
    }

    fun genOverrideOnCreate(): Pair<PsiMethod?, PsiStatement?> {
        val onCreateMethod = elementFactory.createMethodFromText(
            "" +
                    "protected void onCreate(Bundle savedInstanceState) { }", psiClass
        )
        val callSuperStatement =
            elementFactory.createStatementFromText("super.onCreate(savedInstanceState);\n", psiClass)
        val firstMethod = psiClass.methods.firstOrNull()
        writeAction {
            onCreateMethod.lastChild.add(callSuperStatement)
            if (firstMethod != null) {
                psiClass.addAfter(onCreateMethod, firstMethod)
            } else {
                psiClass.add(onCreateMethod)
            }
//            callback(Pair(onCreateMethod, callSuperStatement))
        }
        return Pair(onCreateMethod, callSuperStatement)
    }


    private fun findStatement(psiClass: PsiClass, predicate: Predicate<PsiStatement>): Pair<PsiMethod?, PsiStatement?> {
        var bindState: PsiStatement? = null
        val bindMethod = psiClass.methods.find { psiMethod ->
            bindState = psiMethod.body?.statements?.find { psiStatement ->
                predicate.test(psiStatement)
            }
            bindState != null
        }
        return Pair(bindMethod, bindState)
    }

    private fun log(content: String){
        Notifier.notifyInfo(project!!, content)
    }

    // 遍历psiFields找到包含BindView注解的字段
    private fun collectBindViewAnnotation(psiFields: Array<PsiField>): Pair<MutableMap<String, PsiField>, MutableList<PsiAnnotation>> {
        val knifeFields: MutableMap<String, PsiField> = mutableMapOf() // eg: {"rv_list": PsiField}
        val bindViewAnnotations = mutableListOf<PsiAnnotation>()
        psiFields.forEach {
            it.annotations.forEach { psiAnnotation: PsiAnnotation ->
                // 记录这个psiField, 将BindView注解删掉
                if (psiAnnotation.qualifiedName?.contains("BindView") == true) {
                    val R_id_view = psiAnnotation.findAttributeValue("value")?.text?.replace("R2", "R")
                    if (R_id_view != null) {
                        knifeFields[R_id_view] = it
                        bindViewAnnotations.add(psiAnnotation)
                    }
                }
            }
        }
        return Pair(knifeFields, bindViewAnnotations)
    }

    private fun insertBindClickMethod(
        psiClass: PsiClass,
        onClickVOs: MutableList<BindClickVO>,
        onClickAnnotations: MutableList<PsiAnnotation>
    ) {
        if (onClickVOs.isEmpty()) {
            return
        }
        writeAction {
            // 构建__bindClicks()方法体
            var caller = ""
            val bindClickMethod = if (butterknifeView == null) {
                elementFactory.createMethodFromText("private void __bindClicks() {}\n", psiClass)
            } else {
                caller = "${butterknifeView}."
                elementFactory.createMethodFromText(
                    "private void __bindClicks(View ${butterknifeView}) {}\n", psiClass
                )
            }
            //importMyDebouncingListenerIfAbsent()
            onClickVOs.forEach {
                //lambda 转化
                val setClickState = elementFactory.createStatementFromText(
                    "${caller}findViewById(${it.viewId}).setOnClickListener(this::${it.callMethodExpr.subSequence(0, it.callMethodExpr.indexOf("("))});",
                    psiClass
                )
                bindClickMethod.lastChild.add(setClickState)
            }
            val para = if (butterknifeView == null) "" else butterknifeView
            val callBindClickState = elementFactory.createStatementFromText("__bindClicks($para);\n", psiClass)
            // 插入__bindClicks()调用
            anchorStatement = anchorMethod?.addAfter(callBindClickState, anchorStatement) as? PsiStatement
            // 插入__bindClicks()方法体
            psiClass.addAfter(bindClickMethod, anchorMethod)
            onClickAnnotations.forEach {
                it.delete()
            }
            Logger.info("__bindClicks: ${bindClickMethod.text}")
        }
    }

    @Deprecated("java 8已用lambda快捷实现")
    private fun importMyDebouncingListenerIfAbsent() {
        deBouncingClass ?: return
        val debouncingImportState = psiJavaFile.importList?.importStatements?.find {
            it.qualifiedName?.contains("DebouncingOnClickListener") == true
        }
        // import列表不存在DebouncingOnClickListener类，import它
        if (debouncingImportState == null) {
            writeAction {
                val statement = elementFactory.createImportStatement(deBouncingClass!!)
                psiJavaFile.addBefore(statement, psiJavaFile.importList?.importStatements?.lastOrNull())
            }
        }
    }

    private fun collectOnClickAnnotation(psiClass: PsiClass): Pair<MutableList<BindClickVO>, MutableList<PsiAnnotation>> {
        val onClickVOs: MutableList<BindClickVO> = mutableListOf()
        val annotations: MutableList<PsiAnnotation> = mutableListOf()
        psiClass.methods.forEach { method: PsiMethod ->
            method.annotations.forEach { annotation: PsiAnnotation ->
                if (annotation.qualifiedName?.contains("OnClick") == true) {
                    // 收集注解中的id
                    val attributeValue: PsiAnnotationMemberValue? = annotation.findAttributeValue("value")
                    // @OnClick()中是{id, id，...}或{id}的情况
                    if (attributeValue is PsiArrayInitializerMemberValue) {
                        attributeValue.initializers.forEach {
                            addBindClickVo(onClickVOs, method, it)
                        }
                    } else if (attributeValue is PsiAnnotationMemberValue) {
                        // @OnClick()中是单个id的情况
                        addBindClickVo(onClickVOs, method, attributeValue)
                    }
                    annotations.add(annotation)
                }
            }
        }
        return Pair(onClickVOs, annotations)
    }

    private fun addBindClickVo(
        onClickVOs: MutableList<BindClickVO>,
        method: PsiMethod,
        annotationMemberValue: PsiAnnotationMemberValue
    ) {
        val viewId = annotationMemberValue.text.replace("R2.", "R.")
        val lambdaParam = "_${if (butterknifeView.isNullOrEmpty()) "v" else butterknifeView}"
        val methodParam = if (method.parameterList.parameters.isNotEmpty()) lambdaParam else ""
        onClickVOs.add(BindClickVO(viewId, lambdaParam, "${method.name}($methodParam)"))
    }

    private fun implementViewClick() {
        val fullPkgName = "android.view.View.OnClickListener"
        val clickImpl = psiClass.implementsList?.referencedTypes?.find {
            it.canonicalText.contains("View.OnClickListener")
        }
        if (clickImpl != null) {
            return
        }
        val ref = elementFactory.createPackageReferenceElement(fullPkgName)
        val refClass = elementFactory.createType(ref)
        val referenceElement = elementFactory.createReferenceElementByType(refClass)
        writeAction {
            psiClass.implementsList?.add(referenceElement)
        }
    }

    private fun findClazz(project: Project?,  clazzName: String?): Optional<PsiClass> {
        //找到一个类
        return Optional.ofNullable(
            JavaPsiFacade.getInstance(project!!).findClass(clazzName!!, GlobalSearchScope.allScope(project))
        )
    }


    private fun writeAction(commandName: String = "RemoveButterknifeWriteAction", runnable: Runnable) {
        try {
            WriteCommandAction.runWriteCommandAction(project, commandName, "RemoveButterknifeGroupID", runnable, psiJavaFile)
        } catch (e: Exception) {
            e.printStackTrace()
            //log(e.message.orEmpty())
        }
//        ApplicationManager.getApplication().runWriteAction(runnable)
    }

    @Deprecated("用lambda替换 省去onClick的导包")
    private fun replaceDebouncingOnClickListener() {
        if (deBouncingClass == null) {
            val fullClassName = "DebouncingOnClickListener"
            val searchScope = GlobalSearchScope.allScope(project!!)
            val psiClasses = PsiShortNamesCache.getInstance(project).getClassesByName(fullClassName, searchScope)
            deBouncingClass = psiClasses.find {
                it.qualifiedName?.contains("butterknife") == false
            }
        }
        val importDebouncingStatement = psiJavaFile.importList?.importStatements?.find {
            it.qualifiedName?.contains("butterknife.internal.DebouncingOnClickListener") == true
        }
        if (importDebouncingStatement == null) {
            return
        }
        runWriteAction {
            if (deBouncingClass != null) {
                val statement = elementFactory.createImportStatement(deBouncingClass!!)
                try {
                    psiJavaFile.addBefore(statement, importDebouncingStatement)
                    importDebouncingStatement.delete()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                Notifier.notifyError(project!!, "RemoveButterKnife tool: ${psiClass.name}没有找到可替代的DebouncingOnClickListener类，跳过！")
            }
        }
    }

    private fun handleInnerClass(innerClasses: Array<PsiClass>?) {
        innerClasses?.forEach {
            anchorMethod = null
            anchorStatement = null
            butterknifeView = null
            butterknifeBindStatement = null
            val (bindViewFields, bindViewAnnotations) = collectBindViewAnnotation(it.fields)
            val (bindClickVos, onClickAnnotations) = collectOnClickAnnotation(it)
            if (bindClickVos.isNotEmpty() || bindViewFields.isNotEmpty()) {
                val pair = findAnchors(it)
                if (pair.second == null || pair.first == null) {
                    Notifier.notifyError(project!!, "RemoveButterKnife tools: 未在内部类${it.name}找到合适的代码插入位置，跳过 with pair${pair}")
                } else {
                    anchorMethod = pair.first
                    anchorStatement = pair.second
                    //内部类统计
                    if (anchorStatement?.text?.startsWith(mButterKnifeBindEntry) == true){
                        TrackKit.modifyAfterButterKnifeBind(it)
                    } else {
                        TrackKit.trackOtherModify(it, anchorMethod?.name.orEmpty())
                    }
                    insertBindClickMethod(it, bindClickVos, onClickAnnotations)
                    insertBindViewsMethod(it, bindViewFields, bindViewAnnotations)
                }
            } else {
                TrackKit.onDeleteImport(it)
            }
            deleteButterKnifeStatement(it)
            handleInnerClass(it.innerClasses)
        }
    }
}