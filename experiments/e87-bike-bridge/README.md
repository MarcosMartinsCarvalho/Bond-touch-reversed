# Android bike-to-badge field tester

Package `com.marco.e87bikebridge`. Build: `gradlew.bat assembleDebug` (JDK17/SDK36). Choose your own badge/bike by scan; private preset addresses have been removed.

Qix descriptor writes are serialized. The bridge binds, enters interpretation display context, enables streaming, then sends compact speed strings. Bike parser recognizes the app-derived Super73 service or standard CSC.

[Historical setup and tests](../../research/notes/10_e87_bike_bridge-README.md). Physical bike and display behavior need confirmation.
