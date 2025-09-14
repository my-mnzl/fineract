import mysql.connector

host = "localhost"
port = 3306
username = "root"
password = "mysql"
database = "fineract_default"

def wipe_all_loans_truncate():
    conn = mysql.connector.connect(
        host=host,
        port=port,
        user=username,
        password=password,
        database=database,
        charset="utf8mb4",
        collation="utf8mb4_general_ci"  # MariaDB-safe collation
    )
    cursor = conn.cursor()
    cursor.execute("SET FOREIGN_KEY_CHECKS=0;")

    tables = [
        # children first
        "m_loan_topup",
        "m_loan_buy_down_fee_balance",
        "m_loan_capitalized_income_balance",
        "m_loan_collateral",
        "m_loan_account_locks",
        "m_loan_status_change_history",
        "m_loan_rate",
        "m_loan_collateral_management",
        "m_loan_recalculation_details",
        "m_loan_repayment_schedule",
        "m_loan_repayment_schedule_history",
        "m_loan_approved_amount_history",
        "m_note",
        "m_external_asset_owner_transfer",
        "m_external_asset_owner_transfer_loan_mapping",
        "m_loan_disbursement_detail",
        "m_repayment_with_post_dated_checks",
        "m_loan_delinquency_action",
        "m_loan_credit_allocation_rule",
        "m_loan_term_variations",
        "m_loan_progressive_model",
        "m_portfolio_account_associations",
        # charge-related
        "m_loan_installment_charge",
        "m_loan_overdue_installment_charge",
        "m_loan_charge_paid_by",
        "m_loan_charge",
        # officer + arrears + guarantors
        "m_loan_officer_assignment_history",
        "m_loan_arrears_aging",
        "m_guarantor_funding_details",
        "m_guarantor",
        # allocations and transfers
        "m_loan_payment_allocation_rule",
        "m_account_transfer_details",
        "m_account_transfer_transaction",
        "m_account_transfer_standing_instructions",
        "m_account_transfer_standing_instructions_history",
        # savings side-effects
        "m_savings_account_transaction",
        # tranche + delinquency tags
        "m_loan_tranche_charges",
        "m_loan_installment_delinquency_tag",
        "m_loan_delinquency_tag_history",
        # transactions and mappings
        "m_loan_transaction_repayment_schedule_mapping",
        "m_loan_transaction",
        "m_loan_reschedule_request",
        # finally root
        "m_loan"
    ]

    for table in tables:
        try:
            cursor.execute(f"TRUNCATE TABLE {table};")
            print(f"Truncated {table}")
        except mysql.connector.Error as e:
            print(f"Error truncating {table}: {e}")

    cursor.execute("SET FOREIGN_KEY_CHECKS=1;")
    cursor.close()
    conn.close()
    print("All loan-related + savings transactions truncated, IDs reset.")

if __name__ == "__main__":
    wipe_all_loans_truncate()
