# Lisa keeps JNI entry points reachable by their exact names.
-keep class com.lisaai.inference.LlamaNative { *; }
-keep interface com.lisaai.inference.TokenCallback { *; }
