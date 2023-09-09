package com.lagradost.cloudstream3.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Context.CLIPBOARD_SERVICE
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.antonydp.ottSyncApi.RoomSyncLibrary
import com.antonydp.ottSyncApi.SyncEvent
import com.antonydp.ottSyncApi.User
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.cloudstream3.AcraApplication.Companion.getActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.databinding.WatchTogetherManagerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class WatchTogetherViewModel : ViewModel() {
    var isManagerShowing: Boolean = false
    var isLoginFormVisible: Boolean = false
    var isCreateFormVisible: Boolean = false
    var isConnectedElementsVisible: Boolean = false
    var roomId: String = ""
    var myID: String? = ""
    lateinit var roomSyncLibrary: RoomSyncLibrary
    var connectedSocket: Boolean = false
    lateinit var syncMessageFlow: Flow<SyncEvent>

    object WatchTogetherEventBus {
        private val _playerEventFlow = MutableStateFlow<SyncEvent?>(null)
        val playerEventFlow: StateFlow<SyncEvent?> = _playerEventFlow

        fun sendPlayerEvent(event: SyncEvent) {
            _playerEventFlow.value = event
        }
    }
}
fun showWatchTogether(context: Context) {
    val viewModel = ViewModelProvider(context as ViewModelStoreOwner).get(WatchTogetherViewModel::class.java)
    if (viewModel.isManagerShowing) {
        // The manager is already showing, no need to show it again.
        return
    }
     if (!viewModel.connectedSocket) run {
         viewModel.roomSyncLibrary = RoomSyncLibrary(app.baseClient)
         viewModel.syncMessageFlow = viewModel.roomSyncLibrary.syncMessageFlow
     }

    viewModel.viewModelScope.launch {
        WatchTogetherViewModel.WatchTogetherEventBus.playerEventFlow.collect { event ->
            if (event != null) {
                when (event) {
                    is SyncEvent.Play -> {
                        if (viewModel.connectedSocket) {
                            viewModel.roomSyncLibrary.sendPlayAction()
                        }
                    }

                    is SyncEvent.Pause -> {
                        if (viewModel.connectedSocket) {
                            viewModel.roomSyncLibrary.sendPauseAction()
                        }
                    }

                    is SyncEvent.PlaybackSpeed -> {
                        if (viewModel.connectedSocket) {
                            viewModel.roomSyncLibrary.sendSetPlaybackRateAction(event.playbackSpeed)
                        }
                    }

                    is SyncEvent.Seek -> {
                        if (viewModel.connectedSocket) {
                            viewModel.roomSyncLibrary.sendSeekAction(event.playbackPosition)
                        }
                    }
                    // Handle other events as needed
                    // ...
                    else -> {}
                }
            }
        }
    }
    viewModel.viewModelScope.launch {
        Log.d("FlowCollect", "Flow collection started")
        viewModel.roomSyncLibrary.syncMessageFlow.collect { syncEvent ->
            handleSyncEvent(syncEvent, viewModel)
        }
        Log.d("FlowCollect", "Flow collection ended")
    }

    val userAdapter = UserAdapter(
        kickUserClickListener = { user ->
            val success = viewModel.roomSyncLibrary.kickUser(user)
            if (!success) {
                Toast.makeText(context, "You need to be an admin to kick the user", Toast.LENGTH_SHORT).show()
            }
        },
        promoteUserClickListener = { user ->
            CoroutineScope(Dispatchers.Main).launch {
                val success = viewModel.roomSyncLibrary.promoteUser(user)
                if (!success) {
                    Toast.makeText(context, "You need to be an admin to promote the user, and the user to promote must be logged in", Toast.LENGTH_SHORT).show()
                }
            }
        }
    )

    val binding: WatchTogetherManagerBinding = WatchTogetherManagerBinding.inflate(
        LayoutInflater.from(context)
    )

    val builder = BottomSheetDialog(context)
    builder.setOnDismissListener {
        // Update the ViewModel when the dialog is dismissed
        viewModel.isManagerShowing = false
        // Add other necessary state updates here
    }
    if (viewModel.isLoginFormVisible) {
        showForm(binding.loginForm, binding.defaultContent, binding.createForm)
    } else if (viewModel.isCreateFormVisible) {
        showForm(binding.createForm, binding.defaultContent, binding.loginForm)
    }
    if (viewModel.isConnectedElementsVisible) {
        loginVisibilities(binding)
        binding.defaultContent.visibility = GONE
        binding.roomIDTextView.text = viewModel.roomId
        binding.roomIDTextView.visibility = VISIBLE
    }
    builder.show()
    viewModel.isManagerShowing = true
    builder.setContentView(binding.root)

    binding.toggleGroup.addOnButtonCheckedListener { _, checkedId, _ ->
        when (checkedId) {
            R.id.loginButton -> {
                showForm(binding.loginForm, binding.defaultContent, binding.createForm)
                viewModel.isLoginFormVisible = true
                viewModel.isCreateFormVisible = false
                viewModel.isConnectedElementsVisible = false
            }
            R.id.createButton -> {
                showForm(binding.createForm, binding.defaultContent, binding.loginForm)
                viewModel.isLoginFormVisible = false
                viewModel.isCreateFormVisible = true
                viewModel.isConnectedElementsVisible = false
            }
        }
    }

    val userRecyclerView = binding.userRecyclerView
    userRecyclerView.layoutManager = LinearLayoutManager(context)
    userRecyclerView.adapter = userAdapter

    binding.watchTogheterJoinButton.setOnClickListener {
        viewModel.roomId = binding.roomIdEditText.text.toString()
        viewModel.connectedSocket = true
        joinRoom(context, viewModel.roomSyncLibrary, userAdapter, viewModel.roomId)

        loginVisibilities(binding)
        binding.roomIDTextView.text = viewModel.roomId
        binding.roomIDTextView.setOnLongClickListener { view ->
            val textToCopy = (view as TextView).text.toString()
            copyTextToClipboard(textToCopy, context)
            true // Return true to indicate that the long click event is consumed
        }
        viewModel.isLoginFormVisible = false
        viewModel.isCreateFormVisible = false
        viewModel.isConnectedElementsVisible = true
    }

    binding.watchTogheterCreateButton.setOnClickListener {
        val username = binding.usernameEditText.text.toString()
        val password = binding.passwordEditText.text.toString()

        CoroutineScope(Dispatchers.Main).launch {
            val roomID = createRoom(context, viewModel.roomSyncLibrary, username, password, binding.connectedElements)
            if (roomID != null) {
                viewModel.roomId = roomID // Update the roomId variable
                viewModel.connectedSocket = true
                binding.roomIDTextView.text = viewModel.roomId
                binding.roomIDTextView.setOnLongClickListener { view ->
                    val textToCopy = (view as TextView).text.toString()
                    copyTextToClipboard(textToCopy, context)
                    true // Return true to indicate that the long click event is consumed
                }
            }
            loginVisibilities(binding)

            viewModel.isLoginFormVisible = false
            viewModel.isCreateFormVisible = false
            viewModel.isConnectedElementsVisible = true
        }
    }

    binding.leaveButton.setOnClickListener {
        binding.loginForm.visibility = GONE
        binding.createForm.visibility = GONE
        binding.createButton.visibility = VISIBLE
        binding.loginButton.visibility = VISIBLE
        binding.leaveButton.visibility = GONE
        binding.connectedElements.visibility = GONE

        userAdapter.updateUserList(emptyList())
        viewModel.connectedSocket = false

        viewModel.isLoginFormVisible = false
        viewModel.isCreateFormVisible = false
        viewModel.isConnectedElementsVisible = false

        leaveRoom(viewModel.roomSyncLibrary)
    }

    binding.reloadButton.setOnClickListener {
        reloadUsers(viewModel.roomId, viewModel.roomSyncLibrary, userAdapter, context)
    }

    binding.playerStart.setOnClickListener {

    }
    builder.show()
}

fun handleSyncEvent(syncEvent: SyncEvent, viewModel: WatchTogetherViewModel) {
    when (syncEvent) {
        is SyncEvent.You -> {
            viewModel.myID = syncEvent.userID
        }
        else -> {}
    }
}

private fun copyTextToClipboard(text: String, context: Context) {
    val clipboard = context.getActivity()?.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Copied Text", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
}
private fun loginVisibilities(binding: WatchTogetherManagerBinding){
    binding.loginForm.visibility = GONE
    binding.createForm.visibility = GONE
    binding.createButton.visibility = GONE
    binding.loginButton.visibility = GONE
    binding.leaveButton.visibility = VISIBLE
    binding.connectedElements.visibility = VISIBLE

}

private fun showForm(form: View, vararg hideForms: View) {
    form.visibility = VISIBLE
    hideForms.forEach { it.visibility = GONE }
}

private fun joinRoom(context: Context, roomSyncLibrary: RoomSyncLibrary, userAdapter: UserAdapter, roomId: String) {
    if (roomId.isNotBlank()) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                roomSyncLibrary.joinRoom(roomId)
                reloadUsers(roomId, roomSyncLibrary, userAdapter, context)
            } catch (e: Exception) {
                Toast.makeText(context, "Error joining room: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    } else {
        Toast.makeText(context, "Please enter a valid Room ID", Toast.LENGTH_SHORT).show()
    }
}

private suspend fun createRoom(context: Context, roomSyncLibrary: RoomSyncLibrary, username: String, password: String, connectedElements: View): String? {
    if (username.isNotBlank() && password.isNotBlank()) {
        try {
            val roomID = roomSyncLibrary.generateRoom(username, password)
            connectedElements.visibility = VISIBLE
            return roomID
        } catch (e: Exception) {
            Toast.makeText(context, "Error creating room: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    } else {
        Toast.makeText(context, "Please enter a valid username and password", Toast.LENGTH_SHORT).show()
    }
    return null // Return null if an error occurs or input is invalid
}
private fun leaveRoom(roomSyncLibrary: RoomSyncLibrary) {
    roomSyncLibrary.leaveRoom()
}

private fun reloadUsers(roomId: String, roomSyncLibrary: RoomSyncLibrary, userAdapter: UserAdapter, context: Context) {
    if (roomId.isNotEmpty()) {
        CoroutineScope(Dispatchers.Main).launch {
            val userList = roomSyncLibrary.getUser(roomId)
            userAdapter.updateUserList(userList ?: emptyList())
        }
    } else {
        Toast.makeText(context, "Please enter a valid Room ID", Toast.LENGTH_SHORT).show()
    }
}

// UserAdapter class remains the same, but we add a function to update the user list efficiently.
class UserAdapter(
    private val kickUserClickListener: (User) -> Unit,
    private val promoteUserClickListener: (User) -> Unit
) : RecyclerView.Adapter<UserAdapter.UserViewHolder>() {
    private var userList: List<User> = emptyList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(R.layout.watch_together_user_item, parent, false)
        return UserViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        val currentUser = userList[position]
        holder.bind(currentUser)
    }

    override fun getItemCount(): Int {
        return userList.size
    }

    fun updateUserList(newUserList: List<User>) {
        val diffResult = DiffUtil.calculateDiff(UserDiffCallback(userList, newUserList))
        userList = newUserList
        diffResult.dispatchUpdatesTo(this)
    }


    inner class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.nameTextView)
        private val idTextView: TextView = itemView.findViewById(R.id.idTextView)
        private val roleTextView: TextView = itemView.findViewById(R.id.roleTextView)
        private val kickButton: Button = itemView.findViewById(R.id.kickButton)
        private val promoteButton: Button = itemView.findViewById(R.id.promoteButton)

        fun bind(user: User) {
            nameTextView.text = user.name
            idTextView.text = user.id
            roleTextView.text = user.role.toString()

            kickButton.setOnClickListener {
                kickUserClickListener(user)
            }
            promoteButton.setOnClickListener {
                promoteUserClickListener(user)
            }
        }
    }
}

class UserDiffCallback(
    private val oldList: List<User>,
    private val newList: List<User>
) : DiffUtil.Callback() {
    override fun getOldListSize(): Int {
        return oldList.size
    }

    override fun getNewListSize(): Int {
        return newList.size
    }

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition].id == newList[newItemPosition].id
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition] == newList[newItemPosition]
    }
}

