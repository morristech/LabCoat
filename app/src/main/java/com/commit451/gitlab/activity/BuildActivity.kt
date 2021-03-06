package com.commit451.gitlab.activity

import android.Manifest
import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Parcelable
import android.support.design.widget.Snackbar
import android.support.design.widget.TabLayout
import android.support.v4.content.ContextCompat
import android.support.v4.view.ViewPager
import android.support.v7.widget.Toolbar
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup

import com.commit451.gitlab.App
import com.commit451.gitlab.R
import com.commit451.gitlab.adapter.BuildSectionsPagerAdapter
import com.commit451.gitlab.event.BuildChangedEvent
import com.commit451.gitlab.model.Account
import com.commit451.gitlab.model.api.Build
import com.commit451.gitlab.model.api.Project
import com.commit451.gitlab.rx.CustomSingleObserver
import com.commit451.gitlab.util.DownloadUtil

import org.parceler.Parcels

import butterknife.BindView
import butterknife.ButterKnife
import com.commit451.gitlab.extension.getDownloadBuildUrl
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import timber.log.Timber

/**
 * Shows the details of a merge request
 */
class BuildActivity : BaseActivity() {

    companion object {

        private val REQUEST_PERMISSION_WRITE_STORAGE = 1337

        private val KEY_PROJECT = "key_project"
        private val KEY_BUILD = "key_merge_request"

        fun newIntent(context: Context, project: Project, build: Build): Intent {
            val intent = Intent(context, BuildActivity::class.java)
            intent.putExtra(KEY_PROJECT, Parcels.wrap(project))
            intent.putExtra(KEY_BUILD, Parcels.wrap(build))
            return intent
        }
    }

    @BindView(R.id.root) lateinit var root: ViewGroup
    @BindView(R.id.toolbar) lateinit var toolbar: Toolbar
    @BindView(R.id.tabs) lateinit var tabLayout: TabLayout
    @BindView(R.id.pager) lateinit var viewPager: ViewPager
    @BindView(R.id.progress) lateinit var progress: View

    lateinit var menuItemDownload: MenuItem

    lateinit var project: Project
    lateinit var build: Build

    private val onMenuItemClickListener = Toolbar.OnMenuItemClickListener { item ->
        when (item.itemId) {
            R.id.action_retry -> {
                progress.visibility = View.VISIBLE
                App.get().gitLab.retryBuild(project.id, build.id)
                        .compose(this@BuildActivity.bindToLifecycle<Build>())
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(object : CustomSingleObserver<Build>() {

                            override fun error(t: Throwable) {
                                Timber.e(t)
                                progress.visibility = View.GONE
                                Snackbar.make(root, R.string.unable_to_retry_build, Snackbar.LENGTH_LONG)
                                        .show()
                            }

                            override fun success(build: Build) {
                                progress.visibility = View.GONE
                                Snackbar.make(root, R.string.build_started, Snackbar.LENGTH_LONG)
                                        .show()
                                App.bus().post(BuildChangedEvent(build))
                            }
                        })
                return@OnMenuItemClickListener true
            }
            R.id.action_erase -> {
                progress.visibility = View.VISIBLE
                App.get().gitLab.eraseBuild(project.id, build.id)
                        .compose(this@BuildActivity.bindToLifecycle<Build>())
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(object : CustomSingleObserver<Build>() {

                            override fun error(t: Throwable) {
                                Timber.e(t)
                                progress.visibility = View.GONE
                                Snackbar.make(root, R.string.unable_to_erase_build, Snackbar.LENGTH_LONG)
                                        .show()
                            }

                            override fun success(build: Build) {
                                progress.visibility = View.GONE
                                Snackbar.make(root, R.string.build_erased, Snackbar.LENGTH_LONG)
                                        .show()
                                App.bus().post(BuildChangedEvent(build))
                            }
                        })
                return@OnMenuItemClickListener true
            }
            R.id.action_cancel -> {
                progress.visibility = View.VISIBLE
                App.get().gitLab.cancelBuild(project.id, build.id)
                        .compose(this@BuildActivity.bindToLifecycle<Build>())
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(object : CustomSingleObserver<Build>() {

                            override fun error(t: Throwable) {
                                Timber.e(t)
                                progress.visibility = View.GONE
                                Snackbar.make(root, R.string.unable_to_cancel_build, Snackbar.LENGTH_LONG)
                                        .show()
                            }

                            override fun success(build: Build) {
                                progress.visibility = View.GONE
                                Snackbar.make(root, R.string.build_canceled, Snackbar.LENGTH_LONG)
                                        .show()
                                App.bus().post(BuildChangedEvent(build))
                            }
                        })
                return@OnMenuItemClickListener true
            }
            R.id.action_download -> {
                checkDownloadBuild()
                return@OnMenuItemClickListener true
            }
        }
        false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_build)
        ButterKnife.bind(this)

        project = Parcels.unwrap<Project>(intent.getParcelableExtra<Parcelable>(KEY_PROJECT))
        build = Parcels.unwrap<Build>(intent.getParcelableExtra<Parcelable>(KEY_BUILD))

        toolbar.title = getString(R.string.build_number) + build.id
        toolbar.setNavigationIcon(R.drawable.ic_back_24dp)
        toolbar.setNavigationOnClickListener { onBackPressed() }
        toolbar.subtitle = project.nameWithNamespace
        toolbar.inflateMenu(R.menu.retry)
        toolbar.inflateMenu(R.menu.erase)
        toolbar.inflateMenu(R.menu.cancel)
        toolbar.inflateMenu(R.menu.download)
        toolbar.setOnMenuItemClickListener(onMenuItemClickListener)
        menuItemDownload = toolbar.menu.findItem(R.id.action_download)
        menuItemDownload.isVisible = build.artifactsFile != null
        setupTabs()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_PERMISSION_WRITE_STORAGE -> {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    downloadBuild()
                }
            }
        }
    }

    private fun setupTabs() {
        val sectionsPagerAdapter = BuildSectionsPagerAdapter(
                this,
                supportFragmentManager,
                project,
                build)

        viewPager.adapter = sectionsPagerAdapter
        tabLayout.setupWithViewPager(viewPager)
    }

    @TargetApi(23)
    private fun checkDownloadBuild() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            downloadBuild()
        } else {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_PERMISSION_WRITE_STORAGE)
        }
    }

    private fun downloadBuild() {
        val account = App.get().getAccount()
        val downloadUrl = build.getDownloadBuildUrl(App.get().getAccount().serverUrl, project)
        Timber.d("Downloading build: " + downloadUrl)
        DownloadUtil.download(this@BuildActivity, account, downloadUrl, build.artifactsFile.fileName)
    }
}
