package com.studysphere.planner;

import java.net.URI;

import com.studysphere.auth.AuthService.Identity;
import com.studysphere.planner.PlannerService.Page;
import com.studysphere.planner.PlannerService.Plan;
import com.studysphere.planner.PlannerService.PlanInput;
import com.studysphere.planner.PlannerService.Reminder;
import com.studysphere.planner.PlannerService.Task;
import com.studysphere.planner.PlannerService.TaskInput;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class PlannerController {
    private final PlannerService planner;

    PlannerController(PlannerService planner) { this.planner = planner; }

    @GetMapping("/plans")
    Page<Plan> plans(@AuthenticationPrincipal Identity identity, @RequestParam(defaultValue = "0") int page,
                     @RequestParam(defaultValue = "20") int size) {
        return planner.plans(identity.id(), page, size);
    }

    @GetMapping("/plans/{id}")
    Plan plan(@AuthenticationPrincipal Identity identity, @PathVariable String id) {
        return planner.plan(identity.id(), requiredId(id));
    }

    @PostMapping("/plans")
    ResponseEntity<Plan> createPlan(@AuthenticationPrincipal Identity identity, @RequestBody PlanInput input) {
        Plan plan = planner.createPlan(identity.id(), input);
        return created("/plans/" + plan.id(), plan);
    }

    @PatchMapping("/plans/{id}")
    Plan updatePlan(@AuthenticationPrincipal Identity identity, @PathVariable String id, @RequestBody PlanInput input) {
        return planner.updatePlan(identity.id(), requiredId(id), input);
    }

    @DeleteMapping("/plans/{id}")
    ResponseEntity<Void> deletePlan(@AuthenticationPrincipal Identity identity, @PathVariable String id) {
        planner.deletePlan(identity.id(), requiredId(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/tasks")
    Page<Task> tasks(@AuthenticationPrincipal Identity identity, @RequestParam(required = false) String from,
                     @RequestParam(required = false) String to, @RequestParam(required = false) String state,
                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return planner.tasks(identity.id(), from, to, state, page, size);
    }

    @GetMapping("/tasks/{id}")
    Task task(@AuthenticationPrincipal Identity identity, @PathVariable String id) {
        return planner.task(identity.id(), requiredId(id));
    }

    @PostMapping("/tasks")
    ResponseEntity<Task> createTask(@AuthenticationPrincipal Identity identity, @RequestBody TaskInput input) {
        Task task = planner.createTask(identity.id(), input);
        return created("/tasks/" + task.id(), task);
    }

    @PatchMapping("/tasks/{id}")
    Task updateTask(@AuthenticationPrincipal Identity identity, @PathVariable String id, @RequestBody TaskInput input) {
        return planner.updateTask(identity.id(), requiredId(id), input);
    }

    @DeleteMapping("/tasks/{id}")
    ResponseEntity<Void> deleteTask(@AuthenticationPrincipal Identity identity, @PathVariable String id) {
        planner.deleteTask(identity.id(), requiredId(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/tasks/{id}/reminders")
    Page<Reminder> reminders(@AuthenticationPrincipal Identity identity, @PathVariable String id,
                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return planner.reminders(identity.id(), requiredId(id), page, size);
    }

    @PostMapping("/tasks/{id}/reminders")
    ResponseEntity<Reminder> createReminder(@AuthenticationPrincipal Identity identity, @PathVariable String id,
                                            @RequestBody ReminderInput input) {
        long taskId = requiredId(id);
        Reminder reminder = planner.createReminder(identity.id(), taskId, input.scheduledAt());
        return created("/tasks/" + taskId + "/reminders/" + reminder.id(), reminder);
    }

    @DeleteMapping("/tasks/{id}/reminders/{reminderId}")
    ResponseEntity<Void> deleteReminder(@AuthenticationPrincipal Identity identity, @PathVariable String id,
                                        @PathVariable String reminderId) {
        planner.deleteReminder(identity.id(), requiredId(id), requiredId(reminderId));
        return ResponseEntity.noContent().build();
    }

    private static long requiredId(String value) {
        Long id = PlannerService.id(value);
        if (id == null) throw PlannerService.invalid("Use a positive decimal record ID.");
        return id;
    }

    private static <T> ResponseEntity<T> created(String path, T body) {
        return ResponseEntity.created(URI.create("/api/v1" + path)).body(body);
    }

    record ReminderInput(String scheduledAt) {}
}
