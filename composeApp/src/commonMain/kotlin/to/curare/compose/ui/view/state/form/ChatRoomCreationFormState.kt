package to.curare.compose.ui.view.state.form

data class ChatRoomCreationFormState(
    val nameField: TextFieldForm = TextFieldForm(),
    val descriptionField: TextFieldForm = TextFieldForm()
)