plugins {

    alias(libs.plugins.android.application)

    alias(libs.plugins.kotlin.android)

    alias(libs.plugins.kotlin.compose)

}



android {

    namespace = "com.fosautomations.pharmacam"

    compileSdk = 35



    defaultConfig {

        applicationId = "com.fosautomations.pharmacam"

        minSdk = 24

        targetSdk = 35

        versionCode = 1

        versionName = "1.0"



        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }



    buildTypes {

        release {

            isMinifyEnabled = false

            proguardFiles(

                getDefaultProguardFile("proguard-android-optimize.txt"),

                "proguard-rules.pro"

            )

        }

    }

    compileOptions {

        sourceCompatibility = JavaVersion.VERSION_11

        targetCompatibility = JavaVersion.VERSION_11

    }


    kotlinOptions {

        jvmTarget = "11"

    }



    buildFeatures {

        compose = true

        viewBinding = true

    }



    androidResources {

        noCompress += "tflite"

    }

}



dependencies {

    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.compose.ui)

    implementation(libs.androidx.compose.ui.graphics)

    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.androidx.compose.material3)


// CameraX

    implementation(libs.androidx.camera.core)

    implementation(libs.androidx.camera.camera2)

    implementation(libs.androidx.camera.lifecycle)

    implementation(libs.androidx.camera.view)

    implementation(libs.androidx.appcompat)

    implementation(libs.androidx.constraintlayout)

    implementation(libs.androidx.cardview)

    implementation(libs.androidx.recyclerview)



// ML Kit Text Recognition

    implementation(libs.mlkit.text.recognition)



// TensorFlow Lite

    implementation(libs.tflite)

    implementation(libs.tflite.support)

    implementation(libs.tflite.metadata)



// Networking

    implementation(libs.okhttp)



    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)

    androidTestImplementation(libs.androidx.espresso.core)

    androidTestImplementation(platform(libs.androidx.compose.bom))

    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)

    debugImplementation(libs.androidx.compose.ui.test.manifest) }