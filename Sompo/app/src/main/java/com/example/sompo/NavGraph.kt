package com.example.sompo

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

sealed class Rota(val caminho: String, val titulo: String) {
    data object Principal : Rota("principal", "Sensor")
    data object Dashboard : Rota("dashboard", "Dashboard")
}

@Composable
fun SompoNavGraph(
    navController: NavHostController = rememberNavController(),
    bleManager: BleManager,
    awsUploader: AwsUploader
) {
    NavHost(navController = navController, startDestination = Rota.Principal.caminho) {

        composable(Rota.Principal.caminho) {
            TelaPrincipal(bleManager = bleManager)
        }

        composable(Rota.Dashboard.caminho) {
            val viewModel: RankingViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { RankingViewModel(awsUploader) }
                }
            )
            DashboardScreen(viewModel = viewModel)
        }
    }
}
