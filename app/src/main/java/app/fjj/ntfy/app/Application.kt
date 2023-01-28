package app.fjj.ntfy.app

import android.app.Application
import app.fjj.ntfy.db.Repository
import app.fjj.ntfy.util.Log

class Application : Application() {
    val repository by lazy {
        val repository = Repository.getInstance(applicationContext)
        if (repository.getRecordLogs()) {
            Log.setRecord(true)
        }
        repository
    }
}
