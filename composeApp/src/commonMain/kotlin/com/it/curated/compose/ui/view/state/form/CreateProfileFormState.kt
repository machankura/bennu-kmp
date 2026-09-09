package com.it.curated.compose.ui.view.state.form

data class CreateProfileFormState(
    val nameField: TextFieldForm = TextFieldForm(),
    val biographyField: TextFieldForm = TextFieldForm()
)