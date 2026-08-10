# Modern LSPosed discovers this entry from META-INF/xposed/java_init.list.
-keep class dev.a2dprouter.system.GlobalVoipA2dpModule {
    public <init>();
    *;
}

-keepattributes Exceptions,InnerClasses,EnclosingMethod
