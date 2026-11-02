# Room en kotlinx.serialization leveren hun eigen consumer-regels mee.

# Room zoekt de gegenereerde database (LibraryDatabase_Impl) op naam op; R8 mag die niet weghalen of hernoemen.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# ML Kit vindt zijn onderdelen via meta-data in het manifest met als waarde de naam van deze
# interface. Hernoemt R8 de interface, dan wordt niets geregistreerd en crasht de scanner.
-keepnames interface com.google.firebase.components.ComponentRegistrar
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
