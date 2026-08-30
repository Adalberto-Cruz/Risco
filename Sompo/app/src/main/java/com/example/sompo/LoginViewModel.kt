package com.example.sompo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoginViewModel(private val awsUploader: AwsUploader) : ViewModel() {
    private val _estado = MutableStateFlow<LoginUiState>(LoginUiState.Ocioso)
    val estado: StateFlow<LoginUiState> = _estado.asStateFlow()

    fun entrar() {
        if (_estado.value is LoginUiState.Autenticando) return // edge case: evita duplo clique
        _estado.value = LoginUiState.Autenticando
        viewModelScope.launch {
            try {
                awsUploader.autenticar() // reaproveita o Amplify signIn + fetchAuthSession já corrigido
                _estado.value = LoginUiState.BemVindo("Adalberto") // fixo por enquanto
            } catch (e: Exception) {
                _estado.value = LoginUiState.Erro(e.message ?: "Falha ao autenticar. Tente novamente.")
            }
        }
    }
}
