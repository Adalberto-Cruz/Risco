package com.example.sompo

sealed interface LoginUiState {
    data object Ocioso : LoginUiState
    data object Autenticando : LoginUiState
    data class BemVindo(val nome: String) : LoginUiState
    data class Erro(val mensagem: String) : LoginUiState
}
