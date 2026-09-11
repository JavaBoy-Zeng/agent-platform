mod automation;
mod workspace;

use tauri::Manager;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            if cfg!(debug_assertions) {
                app.handle().plugin(
                    tauri_plugin_log::Builder::default()
                        .level(log::LevelFilter::Info)
                        .build(),
                )?;
            }
            let config_dir = app.path().app_config_dir()?;
            std::fs::create_dir_all(&config_dir)?;
            app.manage(workspace::WorkspaceState::new(
                config_dir.join("workspaces.json"),
            ));
            app.manage(automation::AutomationRuntimeState::new(
                config_dir.join("automation-preferences.json"),
            ));
            app.manage(workspace::execution::ExecutionState::default());
            workspace::start_grant_reaper(app.handle().clone());
            Ok(())
        })
        .on_window_event(|window, event| {
            if matches!(event, tauri::WindowEvent::Destroyed) {
                window
                    .state::<workspace::execution::ExecutionState>()
                    .cleanup_window(window.label());
                window
                    .state::<workspace::WorkspaceState>()
                    .cleanup_window(window.label());
            }
        })
        .invoke_handler(tauri::generate_handler![
            workspace::authorize_workspace,
            workspace::revoke_workspace,
            workspace::pick_workspace,
            workspace::list_workspaces,
            workspace::upload_attachments,
            workspace::forget_workspace,
            workspace::list_directory,
            workspace::read_file,
            workspace::workspace_context,
            workspace::execution::workspace_execution_info,
            workspace::execution::workspace_execution_heartbeat,
            workspace::execution::workspace_execution_lock,
            workspace::execution::execute_workspace_operation,
            workspace::execution::cancel_workspace_operation,
            workspace::workspace_file_index,
            workspace::git_status,
            workspace::git_branches,
            workspace::git_switch_branch,
            workspace::git_diff,
            workspace::terminal_create,
            workspace::terminal_write,
            workspace::terminal_resize,
            workspace::terminal_close,
            automation::automation_runtime_info,
            automation::set_automation_keep_awake,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
