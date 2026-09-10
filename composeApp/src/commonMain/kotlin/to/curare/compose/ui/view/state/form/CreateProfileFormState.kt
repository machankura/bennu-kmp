package to.curare.compose.ui.view.state.form

data class CreateProfileFormState(
    val nameField: TextFieldForm = TextFieldForm(),
    val biographyField: TextFieldForm = TextFieldForm()
)