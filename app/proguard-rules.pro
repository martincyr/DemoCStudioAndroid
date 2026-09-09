# Adaptive Cards is a JNI/SWIG library. Keep only the member names that native
# code and Util.castTo need instead of keeping every class member in both packages.
-keepclasseswithmembernames,includedescriptorclasses class io.adaptivecards.** {
    native <methods>;
}

-keepclassmembers,allowoptimization class io.adaptivecards.objectmodel.** {
    public static *** dynamic_cast(...);
}
