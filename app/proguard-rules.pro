# 默认不做代码混淆与资源压缩（isMinifyEnabled = false）。
# 理由：第一版优先保证"装上去就能用、崩溃栈可读"。
# 将来要开混淆时，把下面两行打开，并补全反射相关的 keep 规则（org.json 与 Compose 需要）。
#
# -keepattributes *Annotation*, InnerClasses
# -dontnote kotlinx.serialization.**
