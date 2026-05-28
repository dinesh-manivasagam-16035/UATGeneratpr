package com.uat.generator;

import java.util.HashMap;
import java.util.Map;

public final class ModuleSchemas {

    private static final Map<String, String> SCHEMAS = new HashMap<>();

    static {
        SCHEMAS.put("crm.lead",
            "Module: CRM Lead\n"
          + "Key fields: First_Name (string), Last_Name (string, required), Email (email, unique-ish), "
          + "Phone (string), Company (string), Lead_Status (picklist: New, Contacted, Qualified, Lost), "
          + "Lead_Source (picklist), Owner (user lookup), Annual_Revenue (currency).\n"
          + "Standard actions: create, edit, convert (to Account+Contact+Deal), assign, "
          + "blueprint transitions, mass update.\n"
          + "Roles: Standard user, Manager, Admin. Profiles control read/write/delete.\n"
          + "Validation: Email format, required Last_Name, duplicate detection on Email/Phone.");

        SCHEMAS.put("crm.deal",
            "Module: CRM Deal\n"
          + "Key fields: Deal_Name (required), Amount (currency, required), Stage (picklist: "
          + "Qualification, Needs Analysis, Proposal, Negotiation, Closed Won, Closed Lost), "
          + "Closing_Date (date, required), Account_Name (lookup), Owner (user), Probability (%), "
          + "Pipeline (picklist).\n"
          + "Standard actions: create, edit, stage transitions, win/loss reason capture, "
          + "forecast inclusion, conversion to recurring.\n"
          + "Validation: Closing_Date >= today on create, Amount > 0, stage-specific required fields.");

        SCHEMAS.put("crm.contact",
            "Module: CRM Contact\n"
          + "Key fields: First_Name, Last_Name (required), Email (unique-ish), Phone, "
          + "Account_Name (lookup), Title, Mailing_Address, Owner.\n"
          + "Standard actions: create, edit, merge duplicates, link to deals, email/call logging.");

        SCHEMAS.put("desk.ticket",
            "Module: Desk Ticket\n"
          + "Key fields: Subject (required), Description, Contact (lookup, required), "
          + "Department (required), Priority (Low|Medium|High|Urgent), Status (Open|On Hold|"
          + "Escalated|Closed), Channel (Email|Phone|Web|Chat|Social), Assignee (Agent), "
          + "DueDate, Category, Sub_Category.\n"
          + "Standard actions: create (via channels), reply (public/private), assign, transfer "
          + "department, escalate (SLA), close, reopen, merge tickets, link to article.\n"
          + "SLAs apply by Priority+Department. Business hours respected. "
          + "Roles: Agent, Team Lead, Admin, Light user. "
          + "Validation: Subject required, Contact required, department-specific custom fields.");

        SCHEMAS.put("desk.contact",
            "Module: Desk Contact\n"
          + "Key fields: First_Name, Last_Name (required), Email (unique), Phone, Account, Type "
          + "(Contact|Account|Lead), Owner.\n"
          + "Standard actions: create, edit, merge, associate tickets.");
    }

    private ModuleSchemas() {}

    public static String forModule(String moduleKey) {
        if (moduleKey == null) return "Module schema not provided.";
        String key = moduleKey.trim().toLowerCase();
        return SCHEMAS.getOrDefault(key,
            "Custom/unknown module '" + moduleKey + "'. Treat as a generic record-based module "
          + "with create/edit/delete/list, owner-based access, status field, and audit log.");
    }
}
