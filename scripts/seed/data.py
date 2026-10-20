"""Vocabulary for generated demo data: names, departments, batches, companies, roles."""

INSTITUTE_NAME = "IIIT Tiruchirappalli (Demo)"
EMAIL_DOMAIN = "iiitt.ac.in"

# code, roll-number prefix, name, HOD, share of students
DEPARTMENTS = [
    ("CSE", "CS", "Computer Science and Engineering", "Dr. R. Venkatesan", 0.30),
    ("ECE", "EC", "Electronics and Communication Engineering", "Dr. S. Meenakshi", 0.20),
    ("AIDS", "AD", "Artificial Intelligence and Data Science", "Dr. K. Prakash", 0.15),
    ("EEE", "EE", "Electrical and Electronics Engineering", "Dr. P. Lakshmi Narayanan", 0.12),
    ("MECH", "ME", "Mechanical Engineering", "Dr. A. Raghunathan", 0.13),
    ("CIVIL", "CE", "Civil Engineering", "Dr. N. Bhuvaneswari", 0.10),
]
MTECH_DEPARTMENTS = ("CSE", "ECE", "AIDS")

# name, program, graduation year, share of students
BATCHES = [
    ("B.Tech 2023-2027", "btech", 2027, 0.30),
    ("B.Tech 2024-2028", "btech", 2028, 0.30),
    ("B.Tech 2025-2029", "btech", 2029, 0.30),
    ("M.Tech 2025-2027", "mtech", 2027, 0.10),
]
FINAL_YEAR_BATCH = "B.Tech 2023-2027"
PRE_FINAL_BATCH = "B.Tech 2024-2028"
MTECH_BATCH = "M.Tech 2025-2027"

MALE_FIRST = [
    "Aarav", "Aditya", "Akash", "Arjun", "Arun", "Ashwin", "Balaji", "Bharath", "Chandan", "Darshan", "Deepak",
    "Dhanush", "Dinesh", "Ganesh", "Gautham", "Gokul", "Hari", "Harish", "Hemanth", "Ishaan", "Jagadeesh", "Jayanth",
    "Karthik", "Kaushik", "Kiran", "Krishna", "Lokesh", "Madhav", "Manoj", "Mohan", "Muthu", "Naveen", "Nikhil",
    "Nithin", "Pranav", "Prasanth", "Praveen", "Rahul", "Rajesh", "Rakesh", "Ram", "Ravi", "Rohit", "Sai", "Sanjay",
    "Santhosh", "Saravanan", "Sathish", "Shiva", "Siddharth", "Sriram", "Subash", "Sudharsan", "Surya", "Tarun",
    "Tejas", "Udhay", "Varun", "Vasanth", "Vignesh", "Vijay", "Vikram", "Vinay", "Vishal", "Yash", "Yuvan",
    "Abhishek", "Amit", "Aniket", "Ankit", "Ayush", "Debashish", "Harsh", "Kunal", "Mayank", "Rishabh", "Sourav",
]
FEMALE_FIRST = [
    "Aishwarya", "Akshaya", "Ananya", "Anjali", "Aparna", "Archana", "Bhavya", "Deepika", "Divya", "Gayathri",
    "Harini", "Janani", "Kavya", "Keerthana", "Lakshmi", "Madhumitha", "Meera", "Monisha", "Nandhini", "Nisha",
    "Nivetha", "Pavithra", "Pooja", "Preethi", "Priya", "Ramya", "Revathi", "Sahana", "Sandhya", "Shalini",
    "Shreya", "Sindhu", "Sneha", "Sowmya", "Srinidhi", "Swathi", "Tanvi", "Vaishnavi", "Varsha", "Vidhya",
    "Yamini", "Aditi", "Anushka", "Ishita", "Kriti", "Neha", "Riya", "Sakshi", "Simran", "Trisha",
]
SURNAMES = [
    "Iyer", "Iyengar", "Subramanian", "Krishnan", "Raman", "Natarajan", "Sundaram", "Venkatesan", "Ramasamy",
    "Murugan", "Pandian", "Selvam", "Rajendran", "Balasubramanian", "Chandrasekaran", "Ganesan", "Arumugam",
    "Reddy", "Rao", "Naidu", "Chowdary", "Varma", "Raju", "Kumar", "Prasad", "Shetty", "Hegde", "Gowda", "Bhat",
    "Kamath", "Nair", "Menon", "Pillai", "Kurup", "Panicker", "Sharma", "Verma", "Gupta", "Agarwal", "Singh",
    "Yadav", "Mishra", "Tiwari", "Pandey", "Joshi", "Kulkarni", "Deshpande", "Patil", "Jadhav", "Shinde", "Patel",
    "Shah", "Mehta", "Desai", "Banerjee", "Chatterjee", "Mukherjee", "Das", "Bose", "Sen", "Ghosh", "Mohanty",
    "Sahoo", "Mahapatra", "Khan", "Ahmed", "D'Souza", "Fernandes", "Thomas", "George", "Mathew", "Jacob",
]
SKILLS = [
    "Java", "Python", "C++", "C", "JavaScript", "TypeScript", "React", "Node.js", "Spring Boot", "SQL",
    "PostgreSQL", "MongoDB", "Docker", "Kubernetes", "AWS", "Linux", "Git", "Machine Learning", "Deep Learning",
    "TensorFlow", "PyTorch", "Data Structures", "Algorithms", "Go", "Rust", "Embedded C", "Verilog", "VLSI",
    "MATLAB", "Simulink", "AutoCAD", "SolidWorks", "ANSYS", "PLC", "IoT", "Power BI", "Tableau", "Excel",
]
CTC_MIN, CTC_MAX = 350000, 4500000

# name, industry, tier, headquarters, base CTC (INR), main role, job type
COMPANIES = [
    ("Microsoft India", "product", "super_dream", "Hyderabad", 4400000, "Software Engineer", "full_time"),
    ("Google India", "product", "super_dream", "Bengaluru", 4200000, "Software Engineer", "full_time"),
    ("Amazon Development Centre", "product", "super_dream", "Bengaluru", 3600000, "SDE I", "full_time"),
    ("Atlassian", "product", "super_dream", "Bengaluru", 4500000, "Graduate Software Engineer", "full_time"),
    ("Adobe India", "product", "super_dream", "Noida", 3300000, "Member of Technical Staff", "full_time"),
    ("Goldman Sachs", "finance", "super_dream", "Bengaluru", 3000000, "Analyst - Engineering", "full_time"),
    ("Morgan Stanley", "finance", "dream", "Mumbai", 2400000, "Technology Analyst", "full_time"),
    ("JPMorgan Chase", "finance", "dream", "Bengaluru", 1950000, "Software Engineer Program", "full_time"),
    ("Deutsche Bank", "finance", "dream", "Pune", 1900000, "Graduate Analyst", "full_time"),
    ("American Express", "finance", "dream", "Gurugram", 1800000, "Engineer I", "full_time"),
    ("Qualcomm India", "core_engineering", "super_dream", "Hyderabad", 2600000, "Engineer - Hardware", "full_time"),
    ("Texas Instruments", "core_engineering", "super_dream", "Bengaluru", 2500000, "Analog Design Engineer", "full_time"),
    ("NVIDIA", "core_engineering", "super_dream", "Bengaluru", 3200000, "ASIC Design Engineer", "full_time"),
    ("Intel India", "core_engineering", "dream", "Bengaluru", 2000000, "Graduate Technical Intern", "internship_ppo"),
    ("Samsung R&D Institute", "product", "dream", "Bengaluru", 2100000, "Software Engineer", "full_time"),
    ("Cisco Systems", "product", "dream", "Bengaluru", 2300000, "Software Engineer", "full_time"),
    ("Oracle India", "product", "dream", "Hyderabad", 1800000, "Member Technical Staff", "full_time"),
    ("SAP Labs India", "product", "dream", "Bengaluru", 1700000, "Associate Developer", "full_time"),
    ("Zoho Corporation", "product", "regular", "Chennai", 900000, "Member Technical Staff", "full_time"),
    ("Freshworks", "product", "dream", "Chennai", 1600000, "Software Engineer", "full_time"),
    ("Flipkart", "product", "dream", "Bengaluru", 3200000, "SDE 1", "full_time"),
    ("Swiggy", "startup", "dream", "Bengaluru", 2200000, "SDE 1", "full_time"),
    ("Zomato", "startup", "dream", "Gurugram", 2000000, "SDE 1", "full_time"),
    ("PhonePe", "finance", "dream", "Bengaluru", 2600000, "Software Engineer", "full_time"),
    ("Razorpay", "startup", "dream", "Bengaluru", 2400000, "SDE 1", "full_time"),
    ("CRED", "startup", "super_dream", "Bengaluru", 3000000, "Backend Engineer", "full_time"),
    ("Meesho", "startup", "dream", "Bengaluru", 2400000, "SDE 1", "full_time"),
    ("Paytm", "finance", "regular", "Noida", 1200000, "Software Engineer", "full_time"),
    ("Ola Electric", "core_engineering", "dream", "Bengaluru", 1500000, "Graduate Engineer Trainee", "full_time"),
    ("Mu Sigma", "analytics", "regular", "Bengaluru", 650000, "Trainee Decision Scientist", "full_time"),
    ("Fractal Analytics", "analytics", "regular", "Mumbai", 1000000, "Data Scientist", "full_time"),
    ("Tiger Analytics", "analytics", "regular", "Chennai", 900000, "Analyst", "full_time"),
    ("Tata Consultancy Services", "it_services", "mass", "Mumbai", 360000, "Assistant System Engineer", "full_time"),
    ("Infosys", "it_services", "mass", "Bengaluru", 360000, "Systems Engineer", "full_time"),
    ("Wipro", "it_services", "mass", "Bengaluru", 350000, "Project Engineer", "full_time"),
    ("HCLTech", "it_services", "mass", "Noida", 425000, "Graduate Engineer Trainee", "full_time"),
    ("Tech Mahindra", "it_services", "mass", "Pune", 350000, "Associate Software Engineer", "full_time"),
    ("Cognizant", "it_services", "mass", "Chennai", 400000, "Programmer Analyst Trainee", "full_time"),
    ("Accenture", "consulting", "regular", "Bengaluru", 650000, "Associate Software Engineer", "full_time"),
    ("Capgemini", "it_services", "mass", "Mumbai", 425000, "Analyst", "full_time"),
    ("Deloitte USI", "consulting", "regular", "Hyderabad", 800000, "Analyst", "full_time"),
    ("EY GDS", "consulting", "regular", "Kochi", 700000, "Associate Consultant", "full_time"),
    ("KPMG Global Services", "consulting", "regular", "Bengaluru", 750000, "Associate", "full_time"),
    ("Larsen & Toubro", "core_engineering", "regular", "Chennai", 700000, "Graduate Engineer Trainee", "full_time"),
    ("Bosch Global Software", "core_engineering", "regular", "Coimbatore", 800000, "Associate Engineer", "full_time"),
    ("Siemens", "core_engineering", "regular", "Bengaluru", 850000, "Graduate Trainee Engineer", "full_time"),
    ("ABB India", "core_engineering", "regular", "Bengaluru", 750000, "Graduate Engineer", "full_time"),
    ("Ashok Leyland", "core_engineering", "regular", "Chennai", 600000, "Graduate Engineer Trainee", "full_time"),
    ("BHEL", "government", "regular", "Tiruchirappalli", 900000, "Engineer Trainee", "full_time"),
    ("ISRO (VSSC)", "government", "dream", "Thiruvananthapuram", 1000000, "Scientist/Engineer 'SC'", "full_time"),
]

DESIGNATIONS = ["Campus Relations Manager", "Talent Acquisition Lead", "HR Business Partner", "University Recruiter",
                "Engineering Manager", "Senior Recruiter"]
FOLLOW_UP_TITLES = ["Confirm drive date", "Share student resumes", "Send JD clarification", "Schedule pre-placement talk",
                    "Collect offer letters", "Book auditorium", "Call HR about results", "Send placement brochure"]
VENUES = ["Main Auditorium", "Seminar Hall 1", "CSE Lab Block", "Online (Google Meet)", "Admin Block Hall"]


def spread(items, count):
    """count items spread evenly over the list (small demos still mix tiers and industries)."""
    count = max(0, min(count, len(items)))
    return [items[i * len(items) // count] for i in range(count)] if count else []
