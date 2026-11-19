# Optimiseur R8 de la version publiée (0.40, enquête lenteur).
#
# But : la vitesse (Compose optimisé), pas la taille ni le secret. Donc :
# - aucun renommage : les piles d'erreurs des rapports de lenteur restent lisibles ;
# - notre code et les bibliothèques qui utilisent la réflexion ou la sérialisation ne sont jamais retirés ni renommés
#   (seulement optimisés) : rien ne doit manquer à l'exécution.

-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable,Exceptions

# Notre code (télé et partagé).
-keep,allowoptimization class home.iptv.** { *; }

# Serveur de la maison (supabase-kt), réseau (Ktor) et sérialisation : gardés entiers.
-keep,allowoptimization class io.github.jan.supabase.** { *; }
-keep,allowoptimization class io.ktor.** { *; }
-keep,allowoptimization class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Base locale (SQLDelight) et pilotes.
-keep,allowoptimization class app.cash.sqldelight.** { *; }
-keep,allowoptimization class androidx.sqlite.** { *; }
-keep,allowoptimization class io.requery.android.database.** { *; }

# Coroutines : usine du fil principal trouvée par ServiceLoader.
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler { *; }

# Classes de la JVM de bureau citées par des bibliothèques mais jamais utilisées sur Android.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Métadonnées Kotlin (kotlin-reflect, utilisé par des bibliothèques).
-keep class kotlin.Metadata { *; }
