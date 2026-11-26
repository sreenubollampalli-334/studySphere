ALTER TABLE learning_preferences
    ADD COLUMN daily_focus_goal_minutes SMALLINT UNSIGNED,
    ADD CONSTRAINT chk_daily_focus_goal CHECK (daily_focus_goal_minutes BETWEEN 1 AND 1440);
