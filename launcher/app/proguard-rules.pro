# Shrink and optimise, but keep class and method names: the size win comes from dropping unused
# AndroidX code, not from renaming, and readable stack traces matter more in a sideloaded app
# where crash reports arrive as screenshots.
-dontobfuscate

# Inflated by name from res/drawable/pill_bg.xml (<drawable class=...>); R8 would otherwise strip it.
-keep class com.gathin.tvhome.GlassDrawable { public <init>(); }
